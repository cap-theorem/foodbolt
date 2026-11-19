package main

import (
	"context"
	"time"

	spectral "github.com/cap-theorem/spectral-go"
)

func stayRegistered(ctx context.Context, client *spectral.Client, service string, id Identity, logger *Logger) {
	for ctx.Err() == nil {
		lease, err := client.Register(ctx, spectral.Registration{
			Service:    service,
			InstanceID: id.Instance,
			Endpoint:   id.Endpoint,
			TTL:        id.LeaseTTL,
		})
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			logger.Warn("spectral register failed, retrying", "registration", service, "error", err)
			sleep(ctx, time.Second)
			continue
		}
		logger.Info("registered with spectral", "registration", service, "endpoint", id.Endpoint, "ttl", lease.TTL)

		err = client.KeepAlive(ctx, lease)
		if ctx.Err() != nil {
			return
		}
		logger.Warn("spectral lease lost, re-registering", "registration", service, "error", err)
	}
}

func sleep(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}
