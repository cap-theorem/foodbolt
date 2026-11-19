import { randomBytes } from "node:crypto";
import { lookup } from "node:dns/promises";
import { hostname } from "node:os";
import { setTimeout as sleep } from "node:timers/promises";
import type { SpectralClient } from "@cap-theorem/spectral";
import { log } from "./log.ts";

export interface Identity {
  service: string;
  instance: string;
  machine: string;
  port: number;
  endpoint: string;
  leaseTtlMs: number;
  started: number;
}

export interface Hop {
  service: string;
  instance: string;
  machine: string;
  latency_ms: number;
}

export async function loadIdentity(defaultService: string, instancePrefix: string, defaultPort: number): Promise<Identity> {
  const host = hostname();
  const port = Number(env("PORT") ?? defaultPort);
  const ttl = Number(env("LEASE_TTL_SECONDS") ?? 5);

  return {
    service: env("SERVICE_NAME") ?? defaultService,
    instance: env("INSTANCE_ID") ?? `${instancePrefix}-${randomBytes(2).toString("hex")}`,
    machine: env("MACHINE") ?? host,
    port,
    endpoint: env("ADVERTISE_URL") ?? `http://${await advertiseHost(host)}:${port}`,
    leaseTtlMs: (ttl > 0 ? ttl : 5) * 1000,
    started: Date.now(),
  };
}

export function hop(id: Identity, start: number): Hop {
  return {
    service: id.service,
    instance: id.instance,
    machine: id.machine,
    latency_ms: Math.round((performance.now() - start) * 1000) / 1000,
  };
}

export function info(id: Identity) {
  return {
    service: id.service,
    instance: id.instance,
    machine: id.machine,
    endpoint: id.endpoint,
    uptime_s: Math.floor((Date.now() - id.started) / 1000),
  };
}

export async function stayRegistered(client: SpectralClient, id: Identity, signal: AbortSignal): Promise<void> {
  while (!signal.aborted) {
    let lease;
    try {
      lease = await client.register(
        { service: id.service, instanceId: id.instance, endpoint: id.endpoint, ttlMs: id.leaseTtlMs },
        { signal },
      );
    } catch (err) {
      if (signal.aborted) return;
      log("WARN", "spectral register failed, retrying", { service: id.service, error: String(err) });
      await sleep(1000);
      continue;
    }
    log("INFO", "registered with spectral", { service: id.service, instance: id.instance, endpoint: id.endpoint });

    try {
      await client.keepAlive(lease, { signal });
    } catch (err) {
      if (signal.aborted) return;
      log("WARN", "spectral lease lost, re-registering", { service: id.service, error: String(err) });
    }
  }
}

function env(key: string): string | undefined {
  const value = process.env[key];
  return value === undefined || value === "" ? undefined : value;
}

async function advertiseHost(host: string): Promise<string> {
  try {
    const { address } = await lookup(host, { family: 4 });
    return address.startsWith("127.") ? "127.0.0.1" : address;
  } catch {
    return "127.0.0.1";
  }
}
