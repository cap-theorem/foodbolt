package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Logger struct {
	mu sync.Mutex
}

func (l *Logger) Info(msg string, kv ...any)  { l.write("INFO", msg, kv) }
func (l *Logger) Warn(msg string, kv ...any)  { l.write("WARN", msg, kv) }
func (l *Logger) Error(msg string, kv ...any) { l.write("ERROR", msg, kv) }

func (l *Logger) write(level, msg string, kv []any) {
	var b strings.Builder
	b.WriteString(time.Now().UTC().Format("2006-01-02T15:04:05.000Z"))
	b.WriteByte(' ')
	b.WriteString(level)
	b.WriteByte(' ')
	b.WriteString(msg)
	for i := 0; i+1 < len(kv); i += 2 {
		b.WriteByte(' ')
		b.WriteString(fmt.Sprint(kv[i]))
		b.WriteByte('=')
		b.WriteString(formatValue(kv[i+1]))
	}
	b.WriteByte('\n')

	l.mu.Lock()
	defer l.mu.Unlock()
	_, _ = os.Stdout.WriteString(b.String())
}

func formatValue(v any) string {
	var s string
	switch x := v.(type) {
	case string:
		s = x
	case error:
		s = x.Error()
	case float64:
		s = strconv.FormatFloat(x, 'f', 1, 64)
	case time.Duration:
		s = x.String()
	default:
		s = fmt.Sprint(x)
	}
	if s == "" || strings.ContainsAny(s, " \t\n\"=") {
		return strconv.Quote(s)
	}
	return s
}
