package main

import (
	crand "crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"net/url"
	"os"
	"sort"
	"sync"
	"time"
)

type entry struct {
	Service    string    `json:"service"`
	InstanceID string    `json:"instance_id"`
	Endpoint   string    `json:"endpoint"`
	TTL        string    `json:"ttl"`
	ExpiresAt  time.Time `json:"expires_at"`
	token      string
	ttl        time.Duration
}

type directory struct {
	mu        sync.Mutex
	byToken   map[string]*entry
	byService map[string]map[string]*entry
}

func (d *directory) expire(now time.Time) {
	for token, e := range d.byToken {
		if now.After(e.ExpiresAt) {
			delete(d.byToken, token)
			delete(d.byService[e.Service], e.InstanceID)
			slog.Info("lease expired", "service", e.Service, "instance", e.InstanceID)
		}
	}
}

func (d *directory) register(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Service    string `json:"service"`
		InstanceID string `json:"instance_id"`
		Endpoint   string `json:"endpoint"`
		TTL        string `json:"ttl"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	ttl, err := time.ParseDuration(req.TTL)
	u, uerr := url.Parse(req.Endpoint)
	if err != nil || ttl <= 0 || req.Service == "" || req.InstanceID == "" || uerr != nil || u.Host == "" {
		http.Error(w, "invalid registration", http.StatusBadRequest)
		return
	}

	d.mu.Lock()
	defer d.mu.Unlock()
	now := time.Now()
	d.expire(now)

	if _, exists := d.byService[req.Service][req.InstanceID]; exists {
		http.Error(w, "registration already exists", http.StatusConflict)
		return
	}

	b := make([]byte, 16)
	_, _ = crand.Read(b)
	e := &entry{
		Service:    req.Service,
		InstanceID: req.InstanceID,
		Endpoint:   req.Endpoint,
		TTL:        ttl.String(),
		ExpiresAt:  now.Add(ttl),
		token:      hex.EncodeToString(b),
		ttl:        ttl,
	}
	d.byToken[e.token] = e
	if d.byService[e.Service] == nil {
		d.byService[e.Service] = map[string]*entry{}
	}
	d.byService[e.Service][e.InstanceID] = e
	slog.Info("registered", "service", e.Service, "instance", e.InstanceID, "endpoint", e.Endpoint)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"token": e.token, "ttl": e.TTL, "expires_at": e.ExpiresAt})
}

func (d *directory) renew(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	d.mu.Lock()
	defer d.mu.Unlock()
	now := time.Now()
	d.expire(now)

	e, ok := d.byToken[req.Token]
	if !ok {
		http.Error(w, "unknown or expired lease", http.StatusNotFound)
		return
	}
	e.ExpiresAt = now.Add(e.ttl)
	w.WriteHeader(http.StatusAccepted)
}

func (d *directory) lookup(w http.ResponseWriter, r *http.Request) {
	service := r.URL.Query().Get("service")

	d.mu.Lock()
	d.expire(time.Now())
	candidates := make([]*entry, 0, len(d.byService[service]))
	for _, e := range d.byService[service] {
		candidates = append(candidates, e)
	}
	d.mu.Unlock()

	if len(candidates) == 0 {
		http.Error(w, "no provider for "+service, http.StatusNotFound)
		return
	}
	e := candidates[rand.IntN(len(candidates))]
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"service": e.Service, "instance_id": e.InstanceID, "endpoint": e.Endpoint})
}

func (d *directory) dump(w http.ResponseWriter, _ *http.Request) {
	d.mu.Lock()
	d.expire(time.Now())
	all := make([]*entry, 0, len(d.byToken))
	for _, e := range d.byToken {
		all = append(all, e)
	}
	d.mu.Unlock()

	sort.Slice(all, func(i, j int) bool {
		if all[i].Service != all[j].Service {
			return all[i].Service < all[j].Service
		}
		return all[i].InstanceID < all[j].InstanceID
	})
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(all)
}

func main() {
	d := &directory{byToken: map[string]*entry{}, byService: map[string]map[string]*entry{}}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /register", d.register)
	mux.HandleFunc("POST /renew", d.renew)
	mux.HandleFunc("GET /lookup", d.lookup)
	mux.HandleFunc("GET /status", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ready":true,"state":"active"}`))
	})
	mux.HandleFunc("GET /debug/directory", d.dump)

	addr := ":8000"
	if port := os.Getenv("PORT"); port != "" {
		addr = ":" + port
	}
	slog.Info("spectral-stub listening", "addr", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		slog.Error("serve", "error", err)
		os.Exit(1)
	}
}
