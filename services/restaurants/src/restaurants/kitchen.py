from __future__ import annotations

import threading
from dataclasses import dataclass, field
from datetime import datetime, timezone

from restaurants import logs
from restaurants.downstream import CallError, Downstream
from restaurants.identity import Identity, owner_of

CALLBACK_ATTEMPTS = 3
CALLBACK_BACKOFF_S = 1.0
PREPARING_AFTER_S = 1.0


@dataclass
class Ticket:
    ticket_id: str
    order_id: str
    restaurant_id: str
    restaurant_name: str
    lines: list[dict]
    request_id: str
    prep_seconds: float
    created_at: str
    status: str = "accepted"
    cancelled: threading.Event = field(default_factory=threading.Event)

    def to_json(self) -> dict:
        return {
            "ticket_id": self.ticket_id,
            "order_id": self.order_id,
            "restaurant_id": self.restaurant_id,
            "status": self.status,
            "lines": self.lines,
            "created_at": self.created_at,
        }


class Kitchen:
    def __init__(self, ident: Identity, downstream: Downstream) -> None:
        self._ident = ident
        self._downstream = downstream
        self._lock = threading.Lock()
        self._tickets: dict[str, Ticket] = {}
        self.callbacks_ok = 0
        self.callbacks_failed = 0

    def accept(self, order_id: str, restaurant_id: str, restaurant_name: str, lines: list[dict], prep_seconds: float, request_id: str) -> Ticket:
        ticket = Ticket(
            ticket_id=self._ident.new_id(),
            order_id=order_id,
            restaurant_id=restaurant_id,
            restaurant_name=restaurant_name,
            lines=lines,
            request_id=request_id,
            prep_seconds=prep_seconds,
            created_at=now_rfc3339(),
        )
        with self._lock:
            self._tickets[ticket.ticket_id] = ticket
        threading.Thread(target=self._cook, args=(ticket,), name=f"kitchen-{ticket.ticket_id}", daemon=True).start()
        return ticket

    def get(self, ticket_id: str) -> Ticket | None:
        with self._lock:
            return self._tickets.get(ticket_id)

    def cancel(self, ticket_id: str) -> Ticket | None:
        with self._lock:
            ticket = self._tickets.get(ticket_id)
            if ticket is None:
                return None
            if ticket.status != "cancelled":
                previous = ticket.status
                ticket.status = "cancelled"
                ticket.cancelled.set()
                logs.info(
                    "ticket cancelled",
                    ticket_id=ticket.ticket_id,
                    order_id=ticket.order_id,
                    previous_status=previous,
                    request_id=ticket.request_id,
                )
            return ticket

    def counts(self) -> dict:
        with self._lock:
            counts = {"accepted": 0, "preparing": 0, "ready": 0, "cancelled": 0}
            for ticket in self._tickets.values():
                counts[ticket.status] = counts.get(ticket.status, 0) + 1
            return counts

    def _cook(self, ticket: Ticket) -> None:
        scale = self._ident.time_scale
        preparing_at = min(PREPARING_AFTER_S * scale, ticket.prep_seconds)
        if ticket.cancelled.wait(preparing_at):
            return
        if not self._advance(ticket, "accepted", "preparing"):
            return
        logs.info(
            "ticket preparing",
            ticket_id=ticket.ticket_id,
            order_id=ticket.order_id,
            restaurant=ticket.restaurant_id,
            request_id=ticket.request_id,
        )
        self._notify(ticket, "preparing", f"{ticket.restaurant_name} started ticket {ticket.ticket_id}")

        if ticket.cancelled.wait(max(0.0, ticket.prep_seconds - preparing_at)):
            return
        if not self._advance(ticket, "preparing", "ready"):
            return
        logs.info(
            "ticket ready",
            ticket_id=ticket.ticket_id,
            order_id=ticket.order_id,
            restaurant=ticket.restaurant_id,
            request_id=ticket.request_id,
        )
        self._notify(ticket, "food_ready", f"ticket {ticket.ticket_id} ready")

    def _advance(self, ticket: Ticket, expected: str, new: str) -> bool:
        with self._lock:
            if ticket.status != expected:
                return False
            ticket.status = new
            return True

    def _notify(self, ticket: Ticket, event: str, detail: str) -> None:
        service = f"orders/{owner_of(ticket.order_id)}"
        path = f"/v1/orders/{ticket.order_id}/events"
        body = {"event": event, "source": "restaurants", "detail": detail}
        for attempt in range(1, CALLBACK_ATTEMPTS + 1):
            if ticket.cancelled.is_set():
                return
            try:
                self._downstream.post(service, path, body, ticket.request_id)
                with self._lock:
                    self.callbacks_ok += 1
                return
            except CallError as err:
                if err.status in (404, 409):
                    logs.warn(
                        "order rejected kitchen event, stopping",
                        event=event,
                        order_id=ticket.order_id,
                        ticket_id=ticket.ticket_id,
                        status=err.status,
                        error=err.message,
                        request_id=ticket.request_id,
                    )
                    self.cancel(ticket.ticket_id)
                    with self._lock:
                        self.callbacks_failed += 1
                    return
                logs.warn(
                    "kitchen event failed, retrying" if attempt < CALLBACK_ATTEMPTS else "kitchen event failed, giving up",
                    event=event,
                    order_id=ticket.order_id,
                    attempt=attempt,
                    status=err.status,
                    error=err.message,
                    request_id=ticket.request_id,
                )
            if attempt < CALLBACK_ATTEMPTS and ticket.cancelled.wait(CALLBACK_BACKOFF_S):
                return
        with self._lock:
            self.callbacks_failed += 1


def now_rfc3339() -> str:
    ts = datetime.now(timezone.utc)
    return ts.strftime("%Y-%m-%dT%H:%M:%S.") + f"{ts.microsecond // 1000:03d}Z"
