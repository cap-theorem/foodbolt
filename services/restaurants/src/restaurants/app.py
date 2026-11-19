from __future__ import annotations

import secrets
import threading
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse
from pydantic import BaseModel
from spectral_sdk import Client

from restaurants import catalog, logs
from restaurants.downstream import CallError, Downstream
from restaurants.identity import Identity, start_registration
from restaurants.kitchen import Kitchen

ident = Identity.from_env("restaurants", "rst", 8082)
spectral = Client()
downstream = Downstream(spectral, httpx.Client())
kitchen = Kitchen(ident, downstream)

stats_lock = threading.Lock()
stats = {
    "listings": 0,
    "listings_with_eta": 0,
    "estimate_failures": 0,
    "menus": 0,
    "quotes": 0,
    "quotes_rejected": 0,
    "tickets_created": 0,
}


def bump(key: str) -> None:
    with stats_lock:
        stats[key] += 1


@asynccontextmanager
async def lifespan(_: FastAPI):
    logs.info("listening", port=ident.port, endpoint=ident.endpoint, instance=ident.instance, machine=ident.machine)
    stop = start_registration(spectral, ident)
    yield
    stop.set()
    logs.info("shutting down", instance=ident.instance)


app = FastAPI(title="foodbolt restaurants", lifespan=lifespan)


@app.middleware("http")
async def access_log(request: Request, call_next):
    start = time.perf_counter()
    response = await call_next(request)
    if request.url.path != "/healthz":
        logs.info(
            "http",
            method=request.method,
            path=request.url.path,
            status=response.status_code,
            ms=(time.perf_counter() - start) * 1000,
            request_id=request.headers.get("x-request-id"),
        )
    return response


@app.exception_handler(RequestValidationError)
async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
    problems = []
    for err in exc.errors():
        where = ".".join(str(part) for part in err.get("loc", ()) if part != "body")
        problems.append(f"{where}: {err.get('msg', 'invalid')}" if where else str(err.get("msg", "invalid")))
    return JSONResponse({"error": "; ".join(problems) or "invalid request"}, status_code=422)


def error(status: int, message: str) -> JSONResponse:
    return JSONResponse({"error": message}, status_code=status)


def request_id_or_new(header: str | None) -> str:
    return header or f"req_{secrets.token_hex(8)}"


class QuoteItem(BaseModel):
    item_id: str
    quantity: int


class QuoteRequest(BaseModel):
    restaurant_id: str
    items: list[QuoteItem]


class TicketLine(BaseModel):
    item_id: str
    name: str = ""
    quantity: int


class TicketRequest(BaseModel):
    order_id: str
    restaurant_id: str
    lines: list[TicketLine]


@app.get("/v1/restaurants")
def list_restaurants(lat: float | None = None, lng: float | None = None, x_request_id: str | None = Header(default=None)):
    start = time.perf_counter()
    request_id = request_id_or_new(x_request_id)
    bump("listings")
    entries = [r.summary() for r in catalog.RESTAURANTS]
    downstream_hops: list[dict] = []

    if lat is not None and lng is not None:
        body = {
            "dropoff": {"lat": lat, "lng": lng},
            "pickups": [{"id": r.id, "lat": r.lat, "lng": r.lng} for r in catalog.RESTAURANTS],
        }
        try:
            called = downstream.post("dispatch", "/v1/estimates", body, request_id, timeout=2.0)
            estimates = {e["id"]: e for e in called.body.get("estimates", [])}
            for entry in entries:
                estimate = estimates.get(entry["id"])
                if estimate is None:
                    continue
                entry["distance_km"] = estimate["distance_km"]
                entry["eta_seconds"] = round(entry["prep_seconds"] * ident.time_scale + estimate["travel_seconds"])
            entries.sort(key=lambda e: e.get("eta_seconds", float("inf")))
            downstream_hops = called.body.get("hops", [])
            bump("listings_with_eta")
        except (CallError, KeyError, TypeError) as err:
            bump("estimate_failures")
            logs.warn("dispatch estimates unavailable, listing without ETAs", error=str(err), request_id=request_id)

    return {"restaurants": entries, "hops": [ident.hop(start), *downstream_hops]}


@app.get("/v1/restaurants/{restaurant_id}")
def get_restaurant(restaurant_id: str):
    restaurant = catalog.BY_ID.get(restaurant_id)
    if restaurant is None:
        return error(404, f"unknown restaurant {restaurant_id}")
    bump("menus")
    return restaurant.detail()


@app.post("/v1/quotes")
def quote(req: QuoteRequest, x_request_id: str | None = Header(default=None)):
    start = time.perf_counter()
    request_id = request_id_or_new(x_request_id)
    restaurant = catalog.BY_ID.get(req.restaurant_id)
    if restaurant is None:
        bump("quotes_rejected")
        return error(404, f"unknown restaurant {req.restaurant_id}")
    if not restaurant.open:
        bump("quotes_rejected")
        logs.info("quote rejected", restaurant=restaurant.id, reason="closed", request_id=request_id)
        return error(409, f"{restaurant.name} is closed")
    if not req.items:
        bump("quotes_rejected")
        return error(422, "items must not be empty")

    lines = []
    for requested in req.items:
        item = restaurant.item(requested.item_id)
        if item is None:
            bump("quotes_rejected")
            return error(422, f"{restaurant.name} has no item {requested.item_id}")
        if not item.available:
            bump("quotes_rejected")
            return error(422, f"{item.name} is unavailable")
        if requested.quantity < 1 or requested.quantity > 20:
            bump("quotes_rejected")
            return error(422, f"quantity for {item.item_id} must be between 1 and 20")
        lines.append({
            "item_id": item.item_id,
            "name": item.name,
            "quantity": requested.quantity,
            "unit_price_cents": item.price_cents,
            "line_total_cents": item.price_cents * requested.quantity,
        })

    subtotal = sum(line["line_total_cents"] for line in lines)
    service_fee = catalog.service_fee_cents(subtotal)
    tax = catalog.tax_cents(subtotal)
    total = subtotal + restaurant.delivery_fee_cents + service_fee + tax
    bump("quotes")
    logs.info("quote issued", restaurant=restaurant.id, lines=len(lines), subtotal_cents=subtotal, total_cents=total, request_id=request_id)

    return {
        "restaurant": {"id": restaurant.id, "name": restaurant.name, "pickup": restaurant.location()},
        "lines": lines,
        "subtotal_cents": subtotal,
        "delivery_fee_cents": restaurant.delivery_fee_cents,
        "service_fee_cents": service_fee,
        "tax_cents": tax,
        "total_cents": total,
        "prep_seconds": restaurant.prep_seconds,
        "hops": [ident.hop(start)],
    }


@app.post("/v1/tickets", status_code=201)
def create_ticket(req: TicketRequest, x_request_id: str | None = Header(default=None)):
    start = time.perf_counter()
    request_id = request_id_or_new(x_request_id)
    restaurant = catalog.BY_ID.get(req.restaurant_id)
    if restaurant is None:
        return error(404, f"unknown restaurant {req.restaurant_id}")
    if not restaurant.open:
        return error(409, f"{restaurant.name} is closed")
    if not req.lines:
        return error(422, "lines must not be empty")
    if "." not in req.order_id:
        return error(422, f"order_id {req.order_id} has no owning instance")

    lines = []
    for line in req.lines:
        item = restaurant.item(line.item_id)
        lines.append({
            "item_id": line.item_id,
            "name": line.name or (item.name if item else line.item_id),
            "quantity": line.quantity,
        })

    ready_in = round(restaurant.prep_seconds * ident.time_scale, 1)
    ticket = kitchen.accept(req.order_id, restaurant.id, restaurant.name, lines, ready_in, request_id)
    bump("tickets_created")
    logs.info(
        "ticket accepted",
        ticket_id=ticket.ticket_id,
        order_id=ticket.order_id,
        restaurant=restaurant.id,
        items=sum(line["quantity"] for line in lines),
        ready_in_s=ready_in,
        request_id=request_id,
    )
    return {
        "ticket_id": ticket.ticket_id,
        "status": "accepted",
        "ready_in_seconds": ready_in,
        "hops": [ident.hop(start)],
    }


@app.get("/v1/tickets/{ticket_id}")
def get_ticket(ticket_id: str):
    ticket = kitchen.get(ticket_id)
    if ticket is None:
        return error(404, f"unknown ticket {ticket_id}")
    return ticket.to_json()


@app.post("/v1/tickets/{ticket_id}/cancel")
def cancel_ticket(ticket_id: str):
    ticket = kitchen.cancel(ticket_id)
    if ticket is None:
        return error(404, f"unknown ticket {ticket_id}")
    return ticket.to_json()


@app.get("/v1/stats")
def get_stats():
    with stats_lock:
        snapshot = dict(stats)
    snapshot["tickets"] = kitchen.counts()
    snapshot["callbacks_ok"] = kitchen.callbacks_ok
    snapshot["callbacks_failed"] = kitchen.callbacks_failed
    return snapshot


@app.get("/info")
def info():
    return ident.info()


@app.get("/healthz", response_class=PlainTextResponse)
def healthz():
    return "ok"
