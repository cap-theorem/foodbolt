from __future__ import annotations

import os
import secrets
import socket
import threading
import time
from dataclasses import dataclass

from spectral_sdk import Client, SpectralError

from restaurants import logs

BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"


@dataclass(frozen=True)
class Identity:
    service: str
    instance: str
    machine: str
    port: int
    endpoint: str
    lease_ttl: float
    time_scale: float
    started: float

    @classmethod
    def from_env(cls, default_service: str, instance_prefix: str, default_port: int) -> Identity:
        hostname = socket.gethostname()
        port = int(env("PORT", str(default_port)))
        ttl = float(env("LEASE_TTL_SECONDS", "5"))
        scale = float(env("TIME_SCALE", "1.0"))
        return cls(
            service=env("SERVICE_NAME", default_service),
            instance=env("INSTANCE_ID", f"{instance_prefix}-{secrets.token_hex(2)}"),
            machine=env("MACHINE", hostname),
            port=port,
            endpoint=env("ADVERTISE_URL", f"http://{advertise_host(hostname)}:{port}"),
            lease_ttl=ttl if ttl > 0 else 5.0,
            time_scale=scale if scale > 0 else 1.0,
            started=time.monotonic(),
        )

    def hop(self, start: float) -> dict:
        return {
            "service": self.service,
            "instance": self.instance,
            "machine": self.machine,
            "latency_ms": round((time.perf_counter() - start) * 1000, 3),
        }

    def info(self) -> dict:
        return {
            "service": self.service,
            "instance": self.instance,
            "machine": self.machine,
            "endpoint": self.endpoint,
            "uptime_s": int(time.monotonic() - self.started),
        }

    def new_id(self) -> str:
        return f"{self.instance}." + "".join(secrets.choice(BASE36) for _ in range(6))


def owner_of(resource_id: str) -> str:
    return resource_id.rsplit(".", 1)[0]


def start_registration(client: Client, ident: Identity) -> threading.Event:
    stop = threading.Event()
    for name in (ident.service, f"{ident.service}/{ident.instance}"):
        threading.Thread(
            target=stay_registered,
            args=(client, ident, name, stop),
            name=f"spectral-registration-{name}",
            daemon=True,
        ).start()
    return stop


def stay_registered(client: Client, ident: Identity, name: str, stop: threading.Event) -> None:
    while not stop.is_set():
        try:
            lease = client.register(name, ident.instance, ident.endpoint, ident.lease_ttl)
        except SpectralError as err:
            logs.warn("spectral register failed, retrying", service=name, instance=ident.instance, error=str(err))
            stop.wait(1.0)
            continue
        logs.info("registered with spectral", service=name, instance=ident.instance, endpoint=ident.endpoint, ttl_s=ident.lease_ttl)
        try:
            client.keep_alive(lease, stop)
        except SpectralError as err:
            logs.warn("spectral lease lost, re-registering", service=name, instance=ident.instance, error=str(err))


def env(key: str, fallback: str) -> str:
    value = os.environ.get(key, "")
    return value if value else fallback


def advertise_host(hostname: str) -> str:
    try:
        addr = socket.gethostbyname(hostname)
    except OSError:
        return "127.0.0.1"
    return "127.0.0.1" if addr.startswith("127.") else addr
