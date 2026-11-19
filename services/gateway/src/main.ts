import { randomBytes } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { SpectralClient } from "@cap-theorem/spectral";
import { authenticate, profile, type Customer } from "./customers.ts";
import { CallError, Downstream, type Method } from "./downstream.ts";
import { hop, info, loadIdentity, stayRegistered, type Hop } from "./identity.ts";
import { log } from "./log.ts";

class HttpError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

interface Ctx {
  requestId: string;
  start: number;
  customer: Customer | undefined;
}

interface OrderSummary {
  order_id?: string;
  customer_id?: string;
  status?: string;
  total_cents?: number;
  restaurant?: { id?: string; name?: string };
  delivery?: { courier?: { name?: string; vehicle?: string }; eta_seconds?: number };
  hops?: Hop[];
}

const id = await loadIdentity("gateway", "gw", 8080);
const spectral = new SpectralClient();
const downstream = new Downstream(spectral);

const stats = {
  requests: 0,
  succeeded: 0,
  client_errors: 0,
  server_errors: 0,
  unauthorized: 0,
  orders_placed: 0,
  orders_declined: 0,
  orders_cancelled: 0,
};

const ORDER_PATH = /^\/v1\/orders\/([A-Za-z0-9_.\-]+)$/;
const CANCEL_PATH = /^\/v1\/orders\/([A-Za-z0-9_.\-]+)\/cancel$/;
const RESTAURANT_PATH = /^\/v1\/restaurants\/([A-Za-z0-9_\-]+)$/;

async function handle(req: IncomingMessage, res: ServerResponse, ctx: Ctx): Promise<number> {
  const url = new URL(req.url ?? "/", "http://gateway");
  const path = url.pathname;
  const method = req.method ?? "GET";

  if (method === "GET" && path === "/info") return sendJson(res, 200, info(id));
  if (method === "GET" && path === "/v1/stats") return sendJson(res, 200, stats);

  if (!path.startsWith("/v1/")) {
    throw new HttpError(404, "not_found", `unknown route ${method} ${path}`);
  }

  stats.requests++;
  const customer = authenticate(req.headers.authorization);
  if (!customer) {
    stats.unauthorized++;
    throw new HttpError(401, "unauthorized", "missing or invalid bearer token");
  }
  ctx.customer = customer;

  if (method === "GET" && path === "/v1/me") {
    return sendJson(res, 200, { ...profile(customer), request_id: ctx.requestId, trace: [hop(id, ctx.start)] });
  }
  if (method === "GET" && path === "/v1/restaurants") {
    const query = `?lat=${customer.address.lat}&lng=${customer.address.lng}`;
    return forward(res, ctx, "restaurants", "GET", `/v1/restaurants${query}`);
  }
  if (method === "GET" && path === "/v1/couriers") {
    return forward(res, ctx, "dispatch", "GET", "/v1/couriers");
  }
  if (method === "POST" && path === "/v1/orders") {
    return placeOrder(req, res, ctx, customer);
  }

  let m = RESTAURANT_PATH.exec(path);
  if (method === "GET" && m) {
    return forward(res, ctx, "restaurants", "GET", `/v1/restaurants/${encodeURIComponent(m[1]!)}`);
  }
  m = ORDER_PATH.exec(path);
  if (method === "GET" && m) {
    const order = await fetchOwnedOrder(ctx, customer, m[1]!);
    return sendDownstream(res, ctx, 200, order);
  }
  m = CANCEL_PATH.exec(path);
  if (method === "POST" && m) {
    return cancelOrder(res, ctx, customer, m[1]!);
  }

  throw new HttpError(404, "not_found", `unknown route ${method} ${path}`);
}

async function placeOrder(req: IncomingMessage, res: ServerResponse, ctx: Ctx, customer: Customer): Promise<number> {
  const body = await readJson(req);
  if (typeof body.restaurant_id !== "string" || body.restaurant_id === "") {
    throw new HttpError(400, "invalid_request", "restaurant_id is required");
  }
  if (!Array.isArray(body.items) || body.items.length === 0) {
    throw new HttpError(400, "invalid_request", "items must be a non-empty array");
  }

  const order = {
    customer_id: customer.id,
    restaurant_id: body.restaurant_id,
    items: body.items,
    dropoff: isObject(body.dropoff) ? body.dropoff : customer.address,
    payment_method: typeof body.payment_method === "string" && body.payment_method !== "" ? body.payment_method : customer.paymentMethod,
    tip_cents: typeof body.tip_cents === "number" ? body.tip_cents : 0,
  };

  log("INFO", "placing order", {
    customer_id: customer.id,
    restaurant_id: order.restaurant_id,
    items: order.items.length,
    payment_method: order.payment_method,
    request_id: ctx.requestId,
  });

  let placed: OrderSummary;
  try {
    ({ value: placed } = await downstream.request<OrderSummary>(ctx.requestId, "orders", "POST", "/v1/orders", order));
  } catch (err) {
    if (err instanceof CallError && err.status === 402) {
      stats.orders_declined++;
      const body = isObject(err.body) ? err.body : {};
      log("WARN", "order declined", {
        customer_id: customer.id,
        order_id: body.order_id,
        decline_code: body.decline_code,
        request_id: ctx.requestId,
      });
    }
    throw err;
  }

  stats.orders_placed++;
  log("INFO", "order placed", {
    order_id: placed.order_id,
    customer_id: customer.id,
    restaurant: placed.restaurant?.name,
    total_cents: placed.total_cents,
    courier: placed.delivery?.courier?.name,
    eta_seconds: placed.delivery?.eta_seconds,
    request_id: ctx.requestId,
  });
  return sendDownstream(res, ctx, 201, placed);
}

async function cancelOrder(res: ServerResponse, ctx: Ctx, customer: Customer, orderId: string): Promise<number> {
  const existing = await fetchOwnedOrder(ctx, customer, orderId);
  const owner = ownerService(orderId);
  const { value } = await callOwner<OrderSummary>(ctx, owner, "POST", `/v1/orders/${encodeURIComponent(orderId)}/cancel`);
  stats.orders_cancelled++;
  log("INFO", "order cancelled", {
    order_id: orderId,
    customer_id: customer.id,
    previous_status: existing.status,
    request_id: ctx.requestId,
  });
  return sendDownstream(res, ctx, 200, value);
}

async function fetchOwnedOrder(ctx: Ctx, customer: Customer, orderId: string): Promise<OrderSummary> {
  const { value } = await callOwner<OrderSummary>(ctx, ownerService(orderId), "GET", `/v1/orders/${encodeURIComponent(orderId)}`);
  if (value.customer_id !== customer.id) {
    log("WARN", "order belongs to another customer", { order_id: orderId, customer_id: customer.id, request_id: ctx.requestId });
    throw new HttpError(404, "not_found", `order ${orderId} not found`);
  }
  return value;
}

async function callOwner<T>(ctx: Ctx, service: string, method: Method, path: string) {
  try {
    return await downstream.request<T>(ctx.requestId, service, method, path);
  } catch (err) {
    if (err instanceof CallError && isObject(err.body) && err.body.no_instance === true) {
      throw new HttpError(404, "not_found", `order not found: ${service} is no longer registered`);
    }
    throw err;
  }
}

function ownerService(orderId: string): string {
  const dot = orderId.lastIndexOf(".");
  if (dot <= 0) throw new HttpError(404, "not_found", `order ${orderId} not found`);
  return `orders/${orderId.slice(0, dot)}`;
}

async function forward(res: ServerResponse, ctx: Ctx, service: string, method: Method, path: string): Promise<number> {
  const { value } = await downstream.request<unknown>(ctx.requestId, service, method, path);
  return sendDownstream(res, ctx, 200, value);
}

function sendDownstream(res: ServerResponse, ctx: Ctx, status: number, value: unknown): number {
  const body: Record<string, unknown> = isObject(value) ? { ...value } : { data: value };
  const hops = Array.isArray(body.hops) ? (body.hops as Hop[]) : [];
  delete body.hops;
  body.request_id = ctx.requestId;
  body.trace = [hop(id, ctx.start), ...hops];
  return sendJson(res, status, body);
}

function toHttpError(err: unknown): HttpError {
  if (err instanceof HttpError) return err;
  if (err instanceof CallError) {
    const body = isObject(err.body) ? err.body : {};
    switch (err.status) {
      case 400:
      case 422:
        return new HttpError(400, "invalid_request", err.message);
      case 402:
        return new HttpError(402, "payment_declined", typeof body.decline_code === "string" ? body.decline_code : err.message);
      case 404:
        return new HttpError(404, "not_found", err.message);
      case 409:
        return new HttpError(409, "conflict", err.message);
      default:
        return new HttpError(503, "service_unavailable", `${err.service}: ${err.message}`);
    }
  }
  return new HttpError(500, "internal_error", String(err));
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new HttpError(400, "invalid_request", "request body must be valid JSON");
  }
  if (!isObject(parsed)) throw new HttpError(400, "invalid_request", "request body must be a JSON object");
  return parsed;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sendJson(res: ServerResponse, status: number, body: unknown): number {
  res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body));
  return status;
}

function newRequestId(): string {
  return `req_${randomBytes(8).toString("hex")}`;
}

const server = createServer((req, res) => {
  const path = (req.url ?? "/").split("?")[0];
  if (req.method === "GET" && path === "/healthz") {
    res.writeHead(200, { "content-type": "text/plain" }).end("ok");
    return;
  }

  const incoming = req.headers["x-request-id"];
  const ctx: Ctx = {
    requestId: typeof incoming === "string" && incoming !== "" ? incoming : newRequestId(),
    start: performance.now(),
    customer: undefined,
  };
  res.setHeader("x-request-id", ctx.requestId);

  handle(req, res, ctx)
    .catch((err: unknown) => {
      const e = toHttpError(err);
      if (e.status >= 500) {
        stats.server_errors++;
        log("WARN", "request failed", { code: e.code, error: e.message, request_id: ctx.requestId });
      } else {
        stats.client_errors++;
      }
      return sendJson(res, e.status, { error: { code: e.code, message: e.message }, request_id: ctx.requestId });
    })
    .then((status) => {
      if (status < 400 && path?.startsWith("/v1/") && path !== "/v1/stats") stats.succeeded++;
      log(status >= 500 ? "WARN" : "INFO", "http", {
        method: req.method,
        path,
        status,
        ms: Math.round((performance.now() - ctx.start) * 10) / 10,
        customer_id: ctx.customer?.id,
        request_id: ctx.requestId,
      });
    });
});

const shutdown = new AbortController();
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    log("INFO", "shutting down", { signal });
    shutdown.abort();
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  });
}

server.listen(id.port, () => {
  log("INFO", "listening", { service: id.service, instance: id.instance, port: id.port, endpoint: id.endpoint });
  void stayRegistered(spectral, id, shutdown.signal);
});
