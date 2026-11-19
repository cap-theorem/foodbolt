import { ServiceNotFoundError, SpectralError, type Provider, type SpectralClient } from "@cap-theorem/spectral";
import { log } from "./log.ts";

export class CallError extends Error {
  readonly service: string;
  readonly status: number;
  readonly body: unknown;

  constructor(service: string, status: number, message: string, body: unknown = undefined) {
    super(message);
    this.service = service;
    this.status = status;
    this.body = body;
  }
}

export interface Called<T> {
  value: T;
  provider: Provider;
}

export type Method = "GET" | "POST";

const TIMEOUT_MS = 15_000;

export class Downstream {
  readonly #spectral: SpectralClient;

  constructor(spectral: SpectralClient) {
    this.#spectral = spectral;
  }

  async request<T>(requestId: string, service: string, method: Method, path: string, body?: unknown): Promise<Called<T>> {
    try {
      return await this.#attempt<T>(requestId, service, method, path, body, undefined);
    } catch (err) {
      if (method !== "GET" || !(err instanceof CallError) || !isRetryable(err)) throw err;
      log("WARN", "retrying on another instance", { service, path, error: err.message, request_id: requestId });
      return await this.#attempt<T>(requestId, service, method, path, body, failedInstance(err));
    }
  }

  async #attempt<T>(requestId: string, service: string, method: Method, path: string, body: unknown, avoid: string | undefined): Promise<Called<T>> {
    const provider = await this.#lookup(requestId, service, avoid);
    const start = performance.now();

    let res: Response;
    try {
      res = await fetch(provider.endpoint + path, {
        method,
        headers: { "content-type": "application/json", "x-request-id": requestId },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
    } catch (err) {
      log("WARN", "call", {
        service,
        instance: provider.instanceId,
        method,
        path,
        status: "unreachable",
        ms: elapsed(start),
        error: String(err),
        request_id: requestId,
      });
      throw new CallError(service, 503, `${provider.instanceId} unreachable: ${String(err)}`, { instance: provider.instanceId });
    }

    const text = await res.text();
    log(res.status >= 500 ? "WARN" : "INFO", "call", {
      service,
      instance: provider.instanceId,
      method,
      path,
      status: res.status,
      ms: elapsed(start),
      request_id: requestId,
    });

    let parsed: unknown = undefined;
    try {
      parsed = text === "" ? undefined : JSON.parse(text);
    } catch {
      parsed = undefined;
    }

    if (!res.ok) {
      const message = errorMessage(parsed) ?? text.slice(0, 4096);
      throw new CallError(service, res.status, message, { ...(isObject(parsed) ? parsed : {}), instance: provider.instanceId });
    }
    if (parsed === undefined) {
      throw new CallError(service, 502, `${provider.instanceId} returned invalid JSON`, { instance: provider.instanceId });
    }
    return { value: parsed as T, provider };
  }

  async #lookup(requestId: string, service: string, avoid: string | undefined): Promise<Provider> {
    let provider: Provider | undefined;
    for (let i = 0; i < 3; i++) {
      try {
        provider = await this.#spectral.lookup(service);
      } catch (err) {
        if (err instanceof ServiceNotFoundError) {
          log("WARN", "spectral lookup found no instance", { service, request_id: requestId });
          throw new CallError(service, 503, `no instance of ${service} is registered`, { no_instance: true });
        }
        if (err instanceof SpectralError) {
          log("WARN", "spectral lookup failed", { service, error: err.message, request_id: requestId });
          throw new CallError(service, 503, `spectral lookup failed: ${err.message}`);
        }
        throw err;
      }
      if (provider.instanceId !== avoid) return provider;
    }
    return provider!;
  }
}

export function isNotFound(err: unknown): boolean {
  return err instanceof CallError && err.status === 404;
}

function isRetryable(err: CallError): boolean {
  if (isObject(err.body) && err.body.no_instance === true) return false;
  return err.status === 503 || err.status === 502;
}

function failedInstance(err: CallError): string | undefined {
  if (isObject(err.body) && typeof err.body.instance === "string") return err.body.instance;
  return undefined;
}

function errorMessage(parsed: unknown): string | undefined {
  if (!isObject(parsed)) return undefined;
  if (typeof parsed.error === "string") return parsed.error;
  if (isObject(parsed.error) && typeof parsed.error.message === "string") return parsed.error.message;
  return undefined;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function elapsed(start: number): number {
  return Math.round((performance.now() - start) * 10) / 10;
}
