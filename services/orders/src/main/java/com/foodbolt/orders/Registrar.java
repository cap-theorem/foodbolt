package com.foodbolt.orders;

import io.github.captheorem.spectral.KeepAlive;
import io.github.captheorem.spectral.Lease;
import io.github.captheorem.spectral.Registration;
import io.github.captheorem.spectral.SpectralClient;
import io.github.captheorem.spectral.SpectralException;

final class Registrar implements Runnable {
    private final SpectralClient client;
    private final Identity id;
    private final String serviceName;

    private Registrar(SpectralClient client, Identity id, String serviceName) {
        this.client = client;
        this.id = id;
        this.serviceName = serviceName;
    }

    static Thread start(SpectralClient client, Identity id, String serviceName) {
        Thread thread = new Thread(new Registrar(client, id, serviceName), "spectral-registration-" + serviceName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Override
    public void run() {
        Registration registration = new Registration(serviceName, id.instance(), id.endpoint(), id.leaseTtl());
        while (!Thread.currentThread().isInterrupted()) {
            Lease lease;
            try {
                lease = client.register(registration);
            } catch (SpectralException e) {
                Log.warn("spectral register failed, retrying", "service", serviceName, "instance", id.instance(), "error", e.getMessage());
                if (!pause(1_000)) {
                    return;
                }
                continue;
            }
            Log.info("registered with spectral", "service", serviceName, "instance", id.instance(), "endpoint", id.endpoint(),
                    "ttl_ms", id.leaseTtl().toMillis());

            try (KeepAlive keeper = client.keepAlive(lease)) {
                while (keeper.isRunning()) {
                    if (!pause(250)) {
                        return;
                    }
                }
                keeper.failure().ifPresent(e -> Log.warn("spectral lease lost, re-registering", "service", serviceName,
                        "instance", id.instance(), "error", e.getMessage()));
            }
        }
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
