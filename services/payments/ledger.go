package main

import (
	"errors"
	"sync"
	"time"
)

const (
	StatusAuthorized = "authorized"
	StatusCaptured   = "captured"
	StatusVoided     = "voided"
	StatusRefunded   = "refunded"
)

const maxAmountCents = 50000

type Payment struct {
	PaymentID     string `json:"payment_id"`
	OrderID       string `json:"order_id"`
	CustomerID    string `json:"customer_id"`
	Status        string `json:"status"`
	AmountCents   int64  `json:"amount_cents"`
	PaymentMethod string `json:"payment_method"`
	CreatedAt     string `json:"created_at"`
	UpdatedAt     string `json:"updated_at"`
}

type Decline struct {
	Code    string
	Message string
}

var approvingMethods = map[string]bool{
	"pm_card_visa":       true,
	"pm_card_mastercard": true,
	"pm_card_amex":       true,
}

var decliningMethods = map[string]Decline{
	"pm_card_declined":           {Code: "card_declined", Message: "card declined"},
	"pm_card_insufficient_funds": {Code: "insufficient_funds", Message: "insufficient funds"},
}

var (
	ErrNotFound          = errors.New("payment not found")
	ErrInvalidTransition = errors.New("invalid transition")
)

type Stats struct {
	Authorized      int   `json:"authorized"`
	Captured        int   `json:"captured"`
	Voided          int   `json:"voided"`
	Refunded        int   `json:"refunded"`
	Declined        int   `json:"declined"`
	ProcessorDown   int   `json:"processor_unavailable"`
	AuthorizedCents int64 `json:"authorized_cents"`
	CapturedCents   int64 `json:"captured_cents"`
	Payments        int   `json:"payments"`
}

type Ledger struct {
	mu        sync.Mutex
	byID      map[string]*Payment
	byOrder   map[string]*Payment
	declined  int
	down      int
	authCents int64
	capCents  int64
}

func NewLedger() *Ledger {
	return &Ledger{byID: map[string]*Payment{}, byOrder: map[string]*Payment{}}
}

func (l *Ledger) Existing(orderID string) (Payment, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	p, ok := l.byOrder[orderID]
	if !ok {
		return Payment{}, false
	}
	return *p, true
}

func (l *Ledger) Authorize(paymentID, orderID, customerID, method string, amount int64) (Payment, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if p, ok := l.byOrder[orderID]; ok {
		return *p, false
	}
	now := nowString()
	p := &Payment{
		PaymentID:     paymentID,
		OrderID:       orderID,
		CustomerID:    customerID,
		Status:        StatusAuthorized,
		AmountCents:   amount,
		PaymentMethod: method,
		CreatedAt:     now,
		UpdatedAt:     now,
	}
	l.byID[paymentID] = p
	l.byOrder[orderID] = p
	l.authCents += amount
	return *p, true
}

func (l *Ledger) RecordDecline() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.declined++
}

func (l *Ledger) RecordProcessorDown() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.down++
}

func (l *Ledger) Get(paymentID string) (Payment, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	p, ok := l.byID[paymentID]
	if !ok {
		return Payment{}, ErrNotFound
	}
	return *p, nil
}

func (l *Ledger) Transition(paymentID, from, to string) (Payment, bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	p, ok := l.byID[paymentID]
	if !ok {
		return Payment{}, false, ErrNotFound
	}
	if p.Status == to {
		return *p, false, nil
	}
	if p.Status != from {
		return *p, false, ErrInvalidTransition
	}
	p.Status = to
	p.UpdatedAt = nowString()
	if to == StatusCaptured {
		l.capCents += p.AmountCents
	}
	return *p, true, nil
}

func (l *Ledger) Stats() Stats {
	l.mu.Lock()
	defer l.mu.Unlock()
	s := Stats{
		Declined:        l.declined,
		ProcessorDown:   l.down,
		AuthorizedCents: l.authCents,
		CapturedCents:   l.capCents,
		Payments:        len(l.byID),
	}
	for _, p := range l.byID {
		switch p.Status {
		case StatusAuthorized:
			s.Authorized++
		case StatusCaptured:
			s.Captured++
		case StatusVoided:
			s.Voided++
		case StatusRefunded:
			s.Refunded++
		}
	}
	return s
}

func nowString() string {
	return time.Now().UTC().Format("2006-01-02T15:04:05.000Z")
}
