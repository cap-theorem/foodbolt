package main

import (
	"context"
	"encoding/json"
	"errors"
	"math/rand/v2"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	spectral "github.com/cap-theorem/spectral-go"
)

type Server struct {
	id          Identity
	ledger      *Ledger
	logger      *Logger
	failureRate float64
}

type AuthorizeRequest struct {
	OrderID       string `json:"order_id"`
	CustomerID    string `json:"customer_id"`
	AmountCents   int64  `json:"amount_cents"`
	PaymentMethod string `json:"payment_method"`
}

type PaymentResponse struct {
	Payment
	Hops []Hop `json:"hops"`
}

func main() {
	logger := &Logger{}
	id := loadIdentity("payments", "pay", 8083)

	failureRate, err := strconv.ParseFloat(env("PAYMENTS_FAILURE_RATE", "0"), 64)
	if err != nil || failureRate < 0 {
		failureRate = 0
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	client, err := spectral.New(spectral.Config{})
	if err != nil {
		logger.Error("spectral client", "error", err)
		os.Exit(1)
	}

	s := &Server{id: id, ledger: NewLedger(), logger: logger, failureRate: failureRate}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/payments/authorize", s.handleAuthorize)
	mux.HandleFunc("POST /v1/payments/{id}/capture", s.transitionHandler(StatusAuthorized, StatusCaptured))
	mux.HandleFunc("POST /v1/payments/{id}/void", s.transitionHandler(StatusAuthorized, StatusVoided))
	mux.HandleFunc("POST /v1/payments/{id}/refund", s.transitionHandler(StatusCaptured, StatusRefunded))
	mux.HandleFunc("GET /v1/payments/{id}", s.handleGet)
	mux.HandleFunc("GET /v1/stats", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, http.StatusOK, s.ledger.Stats()) })
	mux.HandleFunc("GET /info", id.handleInfo)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok")) })

	ln, err := net.Listen("tcp", ":"+strconv.Itoa(id.Port))
	if err != nil {
		logger.Error("listen", "error", err)
		os.Exit(1)
	}
	server := &http.Server{Handler: s.logRequests(mux), ReadHeaderTimeout: 5 * time.Second}

	go stayRegistered(ctx, client, id.Service, id, logger)
	go stayRegistered(ctx, client, id.Service+"/"+id.Instance, id, logger)
	go func() {
		<-ctx.Done()
		logger.Info("shutting down", "instance", id.Instance)
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()

	logger.Info("listening", "service", id.Service, "instance", id.Instance, "port", id.Port, "endpoint", id.Endpoint, "machine", id.Machine, "failure_rate", failureRate)
	if err := server.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("serve", "error", err)
		os.Exit(1)
	}
}

func (s *Server) handleAuthorize(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	requestID := r.Header.Get("X-Request-Id")

	var req AuthorizeRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	if req.OrderID == "" {
		writeError(w, http.StatusBadRequest, "order_id is required")
		return
	}
	if req.AmountCents <= 0 {
		writeError(w, http.StatusBadRequest, "amount_cents must be positive")
		return
	}
	decline, declines := decliningMethods[req.PaymentMethod]
	if !approvingMethods[req.PaymentMethod] && !declines {
		writeError(w, http.StatusBadRequest, "unknown payment_method "+req.PaymentMethod)
		return
	}

	if existing, ok := s.ledger.Existing(req.OrderID); ok {
		s.logger.Info("authorization replayed", "payment_id", existing.PaymentID, "order_id", req.OrderID, "status", existing.Status, "request_id", requestID)
		writeJSON(w, http.StatusOK, PaymentResponse{Payment: existing, Hops: []Hop{s.id.Hop(start)}})
		return
	}

	if s.failureRate > 0 && rand.Float64() < s.failureRate {
		s.ledger.RecordProcessorDown()
		s.logger.Warn("processor unavailable", "order_id", req.OrderID, "amount_cents", req.AmountCents, "request_id", requestID)
		writeError(w, http.StatusServiceUnavailable, "processor unavailable")
		return
	}

	if !declines && req.AmountCents > maxAmountCents {
		decline = Decline{Code: "amount_too_large", Message: "amount exceeds the 50000 cent limit"}
		declines = true
	}
	if declines {
		s.ledger.RecordDecline()
		s.logger.Info("payment declined", "order_id", req.OrderID, "customer_id", req.CustomerID, "method", req.PaymentMethod, "amount_cents", req.AmountCents, "decline_code", decline.Code, "request_id", requestID)
		writeJSON(w, http.StatusPaymentRequired, ErrorResponse{Error: decline.Message, DeclineCode: decline.Code})
		return
	}

	payment, created := s.ledger.Authorize(s.id.NewID(), req.OrderID, req.CustomerID, req.PaymentMethod, req.AmountCents)
	status := http.StatusCreated
	if created {
		s.logger.Info("payment authorized", "payment_id", payment.PaymentID, "order_id", payment.OrderID, "customer_id", payment.CustomerID, "method", payment.PaymentMethod, "amount_cents", payment.AmountCents, "request_id", requestID)
	} else {
		status = http.StatusOK
	}
	writeJSON(w, status, PaymentResponse{Payment: payment, Hops: []Hop{s.id.Hop(start)}})
}

func (s *Server) transitionHandler(from, to string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		requestID := r.Header.Get("X-Request-Id")
		paymentID := r.PathValue("id")

		payment, changed, err := s.ledger.Transition(paymentID, from, to)
		switch {
		case errors.Is(err, ErrNotFound):
			writeError(w, http.StatusNotFound, "payment "+paymentID+" not found")
			return
		case errors.Is(err, ErrInvalidTransition):
			s.logger.Warn("invalid payment transition", "payment_id", paymentID, "status", payment.Status, "wanted", to, "request_id", requestID)
			writeError(w, http.StatusConflict, "payment "+paymentID+" is "+payment.Status+", cannot become "+to)
			return
		}
		if changed {
			s.logger.Info("payment "+to, "payment_id", payment.PaymentID, "order_id", payment.OrderID, "amount_cents", payment.AmountCents, "request_id", requestID)
		}
		writeJSON(w, http.StatusOK, PaymentResponse{Payment: payment, Hops: []Hop{s.id.Hop(start)}})
	}
}

func (s *Server) handleGet(w http.ResponseWriter, r *http.Request) {
	paymentID := r.PathValue("id")
	payment, err := s.ledger.Get(paymentID)
	if err != nil {
		writeError(w, http.StatusNotFound, "payment "+paymentID+" not found")
		return
	}
	writeJSON(w, http.StatusOK, payment)
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		s.logger.Info("http",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"ms", float64(time.Since(start).Microseconds())/1000,
			"request_id", r.Header.Get("X-Request-Id"),
		)
	})
}
