package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"math/big"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"
)

type Identity struct {
	Service  string
	Instance string
	Machine  string
	Port     int
	Endpoint string
	LeaseTTL time.Duration
	Started  time.Time
}

type Hop struct {
	Service   string  `json:"service"`
	Instance  string  `json:"instance"`
	Machine   string  `json:"machine"`
	LatencyMS float64 `json:"latency_ms"`
}

type InfoResponse struct {
	Service  string `json:"service"`
	Instance string `json:"instance"`
	Machine  string `json:"machine"`
	Endpoint string `json:"endpoint"`
	UptimeS  int64  `json:"uptime_s"`
}

func loadIdentity(defaultService, instancePrefix string, defaultPort int) Identity {
	hostname, _ := os.Hostname()
	port, err := strconv.Atoi(env("PORT", strconv.Itoa(defaultPort)))
	if err != nil {
		port = defaultPort
	}
	ttl, err := strconv.ParseFloat(env("LEASE_TTL_SECONDS", "5"), 64)
	if err != nil || ttl <= 0 {
		ttl = 5
	}

	return Identity{
		Service:  env("SERVICE_NAME", defaultService),
		Instance: env("INSTANCE_ID", instancePrefix+"-"+randomHex(2)),
		Machine:  env("MACHINE", hostname),
		Port:     port,
		Endpoint: env("ADVERTISE_URL", "http://"+advertiseHost(hostname)+":"+strconv.Itoa(port)),
		LeaseTTL: time.Duration(ttl * float64(time.Second)),
		Started:  time.Now(),
	}
}

func (id Identity) Hop(start time.Time) Hop {
	return Hop{
		Service:   id.Service,
		Instance:  id.Instance,
		Machine:   id.Machine,
		LatencyMS: float64(time.Since(start).Microseconds()) / 1000,
	}
}

func (id Identity) NewID() string {
	return id.Instance + "." + randomBase36(6)
}

func (id Identity) handleInfo(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, InfoResponse{
		Service:  id.Service,
		Instance: id.Instance,
		Machine:  id.Machine,
		Endpoint: id.Endpoint,
		UptimeS:  int64(time.Since(id.Started).Seconds()),
	})
}

func advertiseHost(hostname string) string {
	addrs, err := net.LookupHost(hostname)
	if err == nil {
		for _, addr := range addrs {
			ip := net.ParseIP(addr)
			if ip != nil && ip.To4() != nil && !ip.IsLoopback() {
				return addr
			}
		}
	}
	return "127.0.0.1"
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

const base36 = "0123456789abcdefghijklmnopqrstuvwxyz"

func randomBase36(n int) string {
	out := make([]byte, n)
	max := big.NewInt(int64(len(base36)))
	for i := range out {
		v, err := rand.Int(rand.Reader, max)
		if err != nil {
			out[i] = '0'
			continue
		}
		out[i] = base36[v.Int64()]
	}
	return string(out)
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

type ErrorResponse struct {
	Error       string `json:"error"`
	DeclineCode string `json:"decline_code,omitempty"`
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, ErrorResponse{Error: msg})
}
