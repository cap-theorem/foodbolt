package com.foodbolt.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

final class Orders {
    record Result(int status, Object body) {}

    private static final int MAX_ORDERS = 20_000;
    private static final int DISPATCH_ATTEMPTS = 3;
    private static final int CALLBACK_ATTEMPTS = 3;
    private static final Map<String, String> EVENT_STATUS = Map.of(
            "preparing", "preparing",
            "food_ready", "ready_for_pickup",
            "picked_up", "out_for_delivery",
            "delivered", "delivered");
    private static final Set<String> EVENTS =
            Set.of("preparing", "food_ready", "courier_at_restaurant", "picked_up", "delivered");

    private final Identity id;
    private final Downstream downstream;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Order> orders = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recent = new ConcurrentLinkedDeque<>();

    private final AtomicLong placed = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong paymentFailed = new AtomicLong();
    private final AtomicLong noCourier = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong events = new AtomicLong();
    private final AtomicLong dispatchRetries = new AtomicLong();
    private final AtomicLong capturedCents = new AtomicLong();

    Orders(Identity id, Downstream downstream, ObjectMapper json) {
        this.id = id;
        this.downstream = downstream;
        this.json = json;
    }

    Result place(JsonNode body, String requestId, long startNanos) {
        String customerId = text(body, "customer_id");
        String restaurantId = text(body, "restaurant_id");
        String paymentMethod = text(body, "payment_method");
        JsonNode items = body.path("items");
        JsonNode dropoff = body.path("dropoff");
        long tipCents = body.path("tip_cents").asLong(0);

        if (customerId == null || restaurantId == null || paymentMethod == null) {
            return error(400, "customer_id, restaurant_id and payment_method are required");
        }
        if (!items.isArray() || items.isEmpty()) {
            return error(400, "items must be a non-empty array");
        }
        if (!dropoff.path("lat").isNumber() || !dropoff.path("lng").isNumber()) {
            return error(400, "dropoff needs numeric lat and lng");
        }
        if (tipCents < 0) {
            return error(400, "tip_cents must not be negative");
        }

        List<JsonNode> hops = new ArrayList<>();

        Downstream.Reply quote;
        try {
            quote = callRetrying(requestId, "restaurants", "POST", "/v1/quotes",
                    Map.of("restaurant_id", restaurantId, "items", items), 2);
        } catch (Downstream.Unavailable e) {
            return error(503, "restaurants unavailable: " + e.getMessage());
        }
        if (!quote.ok()) {
            return passthrough(quote);
        }
        collectHops(hops, quote);

        Order order = new Order(id.newId(), customerId, requestId, Instant.now());
        order.applyQuote(quote.body(), dropoff, paymentMethod, tipCents);
        store(order);
        placed.incrementAndGet();
        Log.info("order placed", "order_id", order.orderId, "customer_id", customerId, "restaurant_id", restaurantId,
                "total_cents", order.totalCents(), "request_id", requestId);

        Map<String, Object> authorize = new LinkedHashMap<>();
        authorize.put("order_id", order.orderId);
        authorize.put("customer_id", customerId);
        authorize.put("amount_cents", order.totalCents());
        authorize.put("payment_method", paymentMethod);

        Downstream.Reply auth;
        try {
            auth = callRetrying(requestId, "payments", "POST", "/v1/payments/authorize", authorize, 2);
        } catch (Downstream.Unavailable e) {
            order.cancel("orders", "payments unavailable");
            cancelled.incrementAndGet();
            Log.warn("order failed", "order_id", order.orderId, "reason", "payments unavailable", "request_id", requestId);
            return error(503, "payments unavailable: " + e.getMessage());
        }
        if (auth.status() == 402) {
            String declineCode = auth.body() == null ? "declined" : auth.body().path("decline_code").asText("declined");
            order.paymentFailed(declineCode);
            paymentFailed.incrementAndGet();
            Log.info("payment declined", "order_id", order.orderId, "decline_code", declineCode, "request_id", requestId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", auth.error());
            out.put("decline_code", declineCode);
            out.put("order_id", order.orderId);
            return new Result(402, out);
        }
        if (!auth.ok()) {
            order.cancel("payments", auth.error());
            cancelled.incrementAndGet();
            Log.warn("order failed", "order_id", order.orderId, "reason", auth.error(), "request_id", requestId);
            return passthrough(auth);
        }
        String paymentId = auth.body().path("payment_id").asText();
        order.paymentAuthorized(paymentId);
        collectHops(hops, auth);
        Log.info("payment authorized", "order_id", order.orderId, "payment_id", paymentId,
                "amount_cents", order.totalCents(), "request_id", requestId);

        Map<String, Object> ticketBody = new LinkedHashMap<>();
        ticketBody.put("order_id", order.orderId);
        ticketBody.put("restaurant_id", restaurantId);
        ticketBody.put("lines", ticketLines(quote.body().path("lines")));

        Downstream.Reply ticket;
        try {
            ticket = callRetrying(requestId, "restaurants", "POST", "/v1/tickets", ticketBody, 2);
        } catch (Downstream.Unavailable e) {
            compensate(order, requestId, "restaurants unavailable");
            return error(503, "restaurants unavailable: " + e.getMessage());
        }
        if (!ticket.ok()) {
            compensate(order, requestId, "kitchen rejected ticket: " + ticket.error());
            return passthrough(ticket);
        }
        String ticketId = ticket.body().path("ticket_id").asText();
        order.ticketAccepted(ticketId);
        collectHops(hops, ticket);
        Log.info("ticket accepted", "order_id", order.orderId, "ticket_id", ticketId, "request_id", requestId);

        long readyIn = ticket.body().path("ready_in_seconds").asLong(quote.body().path("prep_seconds").asLong(0));
        Map<String, Object> deliveryBody = new LinkedHashMap<>();
        deliveryBody.put("order_id", order.orderId);
        deliveryBody.put("pickup", quote.body().path("restaurant").path("pickup"));
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("lat", dropoff.path("lat").asDouble());
        drop.put("lng", dropoff.path("lng").asDouble());
        deliveryBody.put("dropoff", drop);
        deliveryBody.put("ready_in_seconds", readyIn);

        Downstream.Reply delivery = null;
        Set<String> avoid = new HashSet<>();
        for (int attempt = 1; attempt <= DISPATCH_ATTEMPTS && delivery == null; attempt++) {
            Downstream.Reply reply;
            try {
                reply = downstream.call(requestId, "dispatch", "POST", "/v1/deliveries", deliveryBody, avoid);
            } catch (Downstream.Unavailable e) {
                if (e.instance() != null) {
                    avoid.add(e.instance());
                }
                dispatchRetries.incrementAndGet();
                Log.warn("dispatch attempt failed", "order_id", order.orderId, "attempt", attempt,
                        "error", e.getMessage(), "request_id", requestId);
                continue;
            }
            if (reply.ok()) {
                delivery = reply;
            } else if (reply.status() >= 500) {
                avoid.add(reply.instance());
                dispatchRetries.incrementAndGet();
                Log.warn("dispatch instance has no courier, trying another", "order_id", order.orderId,
                        "instance", reply.instance(), "attempt", attempt, "request_id", requestId);
            } else {
                compensate(order, requestId, "dispatch rejected delivery: " + reply.error());
                return passthrough(reply);
            }
        }
        if (delivery == null) {
            noCourier.incrementAndGet();
            compensate(order, requestId, "no courier available");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "no courier available");
            out.put("order_id", order.orderId);
            return new Result(503, out);
        }

        String deliveryId = delivery.body().path("delivery_id").asText();
        JsonNode courier = delivery.body().path("courier");
        long eta = delivery.body().path("eta_seconds").asLong();
        order.courierAssigned(deliveryId, courier, eta);
        collectHops(hops, delivery);
        String courierName = courier.path("name").asText();
        String vehicle = courier.path("vehicle").asText();
        Log.info("courier assigned", "order_id", order.orderId, "delivery_id", deliveryId, "courier", courierName,
                "vehicle", vehicle, "eta_seconds", eta, "request_id", requestId);

        order.confirm("courier " + courierName + " on a " + vehicle);
        confirmed.incrementAndGet();
        Log.info("order confirmed", "order_id", order.orderId, "total_cents", order.totalCents(), "courier", courierName,
                "request_id", requestId);
        forwardReadyIfDue(order);

        ObjectNode out = order.toJson(json);
        ArrayNode outHops = out.putArray("hops");
        outHops.add(json.valueToTree(id.hop(startNanos)));
        hops.forEach(outHops::add);
        return new Result(201, out);
    }

    Result get(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return error(404, "order " + orderId + " not found");
        }
        return new Result(200, order.toJson(json));
    }

    Result list() {
        ArrayNode list = json.createArrayNode();
        Iterator<String> it = recent.descendingIterator();
        while (it.hasNext() && list.size() < 50) {
            Order order = orders.get(it.next());
            if (order != null) {
                list.add(order.toJson(json));
            }
        }
        ObjectNode out = json.createObjectNode();
        out.set("orders", list);
        return new Result(200, out);
    }

    Result event(String orderId, JsonNode body, String requestId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return error(404, "order " + orderId + " not found");
        }
        String event = text(body, "event");
        String source = text(body, "source");
        String detail = text(body, "detail");
        if (event == null || !EVENTS.contains(event)) {
            return error(400, "unknown event " + event);
        }
        String rid = requestId != null ? requestId : order.requestId;

        Order.EventOutcome outcome = order.applyEvent(event, source == null ? "unknown" : source, detail, EVENT_STATUS.get(event));
        if (!outcome.accepted()) {
            Log.info("event ignored, order closed", "order_id", orderId, "event", event, "status", outcome.status(), "request_id", rid);
            return error(409, "order " + orderId + " is " + outcome.status());
        }
        events.incrementAndGet();
        Log.info("order event", "order_id", orderId, "event", event, "source", source, "status", outcome.status(),
                "request_id", rid);

        if (event.equals("food_ready")) {
            forwardReadyIfDue(order);
        }
        if (outcome.becameDelivered()) {
            delivered.incrementAndGet();
            Log.info("order delivered", "order_id", orderId, "total_cents", order.totalCents(), "request_id", rid);
            Order.Payment payment = order.payment();
            if (payment != null) {
                Thread.startVirtualThread(() -> capture(order, payment, rid));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_id", orderId);
        out.put("status", outcome.status());
        return new Result(202, out);
    }

    Result cancel(String orderId, String requestId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return error(404, "order " + orderId + " not found");
        }
        if (!order.cancelIfAllowed("cancelled by customer")) {
            return error(409, "order " + orderId + " is " + order.status() + " and can no longer be cancelled");
        }
        cancelled.incrementAndGet();
        Log.info("order cancelled", "order_id", orderId, "reason", "customer", "request_id", requestId);
        releaseEverything(order, requestId);
        return new Result(200, order.toJson(json));
    }

    Map<String, Object> stats() {
        Map<String, Long> byStatus = new TreeMap<>();
        for (Order order : orders.values()) {
            byStatus.merge(order.status(), 1L, Long::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", orders.size());
        out.put("by_status", byStatus);
        out.put("placed", placed.get());
        out.put("confirmed", confirmed.get());
        out.put("payment_failed", paymentFailed.get());
        out.put("no_courier", noCourier.get());
        out.put("cancelled", cancelled.get());
        out.put("delivered", delivered.get());
        out.put("events", events.get());
        out.put("dispatch_retries", dispatchRetries.get());
        out.put("captured_cents", capturedCents.get());
        return out;
    }

    private void compensate(Order order, String requestId, String reason) {
        order.cancel("orders", reason);
        cancelled.incrementAndGet();
        Log.warn("order failed, compensating", "order_id", order.orderId, "reason", reason, "request_id", requestId);
        releaseEverything(order, requestId);
    }

    private void releaseEverything(Order order, String requestId) {
        String paymentId = order.paymentId();
        if (paymentId != null) {
            Downstream.Reply reply = callOwnerQuietly(requestId, "payments", paymentId, "/v1/payments/" + paymentId + "/void");
            if (reply != null && reply.ok()) {
                order.paymentVoided();
                Log.info("payment voided", "order_id", order.orderId, "payment_id", paymentId, "request_id", requestId);
            }
        }
        String ticketId = order.ticketId();
        if (ticketId != null) {
            Downstream.Reply reply = callOwnerQuietly(requestId, "restaurants", ticketId, "/v1/tickets/" + ticketId + "/cancel");
            if (reply != null && reply.ok()) {
                Log.info("ticket cancelled", "order_id", order.orderId, "ticket_id", ticketId, "request_id", requestId);
            }
        }
        String deliveryId = order.deliveryId();
        if (deliveryId != null) {
            Downstream.Reply reply = callOwnerQuietly(requestId, "dispatch", deliveryId, "/v1/deliveries/" + deliveryId + "/cancel");
            if (reply != null && reply.ok()) {
                Log.info("delivery cancelled", "order_id", order.orderId, "delivery_id", deliveryId, "request_id", requestId);
            }
        }
    }

    private void forwardReadyIfDue(Order order) {
        String deliveryId = order.takeReadyForward();
        if (deliveryId != null) {
            Thread.startVirtualThread(() -> forwardReady(order, deliveryId));
        }
    }

    private void forwardReady(Order order, String deliveryId) {
        String path = "/v1/deliveries/" + deliveryId + "/ready";
        for (int attempt = 1; attempt <= CALLBACK_ATTEMPTS; attempt++) {
            try {
                Downstream.Reply reply = callOwner(order.requestId, "dispatch", deliveryId, "POST", path, Map.of());
                if (reply.ok()) {
                    Log.info("food ready forwarded to dispatch", "order_id", order.orderId, "delivery_id", deliveryId,
                            "request_id", order.requestId);
                    return;
                }
                if (reply.status() == 404 || reply.status() == 409) {
                    Log.warn("dispatch refused food ready", "order_id", order.orderId, "delivery_id", deliveryId,
                            "status", reply.status(), "error", reply.error(), "request_id", order.requestId);
                    return;
                }
            } catch (Downstream.Unavailable e) {
                Log.warn("food ready forward failed", "order_id", order.orderId, "delivery_id", deliveryId,
                        "attempt", attempt, "error", e.getMessage(), "request_id", order.requestId);
            }
            if (!pause(1_000)) {
                return;
            }
        }
        Log.error("gave up forwarding food ready", "order_id", order.orderId, "delivery_id", deliveryId,
                "request_id", order.requestId);
    }

    private void capture(Order order, Order.Payment payment, String requestId) {
        String path = "/v1/payments/" + payment.paymentId() + "/capture";
        for (int attempt = 1; attempt <= CALLBACK_ATTEMPTS; attempt++) {
            try {
                Downstream.Reply reply = callOwner(requestId, "payments", payment.paymentId(), "POST", path, Map.of());
                if (reply.ok()) {
                    order.paymentCaptured();
                    capturedCents.addAndGet(payment.amountCents());
                    Log.info("payment captured", "order_id", order.orderId, "payment_id", payment.paymentId(),
                            "amount_cents", payment.amountCents(), "request_id", requestId);
                    return;
                }
                if (reply.status() == 404 || reply.status() == 409) {
                    Log.warn("payment capture refused", "order_id", order.orderId, "payment_id", payment.paymentId(),
                            "status", reply.status(), "error", reply.error(), "request_id", requestId);
                    return;
                }
            } catch (Downstream.Unavailable e) {
                Log.warn("payment capture failed", "order_id", order.orderId, "payment_id", payment.paymentId(),
                        "attempt", attempt, "error", e.getMessage(), "request_id", requestId);
            }
            if (!pause(1_000)) {
                return;
            }
        }
        Log.error("gave up capturing payment", "order_id", order.orderId, "payment_id", payment.paymentId(),
                "request_id", requestId);
    }

    private Downstream.Reply callRetrying(String requestId, String service, String method, String path, Object body,
            int attempts) throws Downstream.Unavailable {
        Set<String> avoid = new HashSet<>();
        for (int attempt = 1; ; attempt++) {
            try {
                Downstream.Reply reply = downstream.call(requestId, service, method, path, body, avoid);
                if (reply.status() < 500 || attempt >= attempts) {
                    return reply;
                }
                avoid.add(reply.instance());
                Log.warn("retrying on another instance", "service", service, "path", path, "status", reply.status(),
                        "attempt", attempt, "request_id", requestId);
            } catch (Downstream.Unavailable e) {
                if (attempt >= attempts) {
                    throw e;
                }
                if (e.instance() != null) {
                    avoid.add(e.instance());
                }
                Log.warn("retrying on another instance", "service", service, "path", path, "error", e.getMessage(),
                        "attempt", attempt, "request_id", requestId);
            }
        }
    }

    private Downstream.Reply callOwner(String requestId, String service, String resourceId, String method, String path,
            Object body) throws Downstream.Unavailable {
        String owner = Identity.owner(resourceId);
        if (owner == null) {
            throw new Downstream.Unavailable("malformed id " + resourceId, null);
        }
        return downstream.call(requestId, service + "/" + owner, method, path, body, Set.of());
    }

    private Downstream.Reply callOwnerQuietly(String requestId, String service, String resourceId, String path) {
        try {
            Downstream.Reply reply = callOwner(requestId, service, resourceId, "POST", path, Map.of());
            if (!reply.ok()) {
                Log.warn("release failed", "service", service, "id", resourceId, "status", reply.status(),
                        "error", reply.error(), "request_id", requestId);
            }
            return reply;
        } catch (Downstream.Unavailable e) {
            Log.warn("release failed", "service", service, "id", resourceId, "error", e.getMessage(), "request_id", requestId);
            return null;
        }
    }

    private void store(Order order) {
        orders.put(order.orderId, order);
        recent.addLast(order.orderId);
        while (orders.size() > MAX_ORDERS) {
            String oldest = recent.pollFirst();
            if (oldest == null) {
                break;
            }
            orders.remove(oldest);
        }
    }

    private ArrayNode ticketLines(JsonNode quoteLines) {
        ArrayNode lines = json.createArrayNode();
        for (JsonNode line : quoteLines) {
            ObjectNode out = lines.addObject();
            out.put("item_id", line.path("item_id").asText());
            out.put("name", line.path("name").asText());
            out.put("quantity", line.path("quantity").asInt());
        }
        return lines;
    }

    private static void collectHops(List<JsonNode> hops, Downstream.Reply reply) {
        if (reply.body() != null && reply.body().path("hops").isArray()) {
            reply.body().path("hops").forEach(hops::add);
        }
    }

    private static Result passthrough(Downstream.Reply reply) {
        int status = reply.status() >= 500 ? 503 : reply.status();
        return error(status, reply.error());
    }

    private static Result error(int status, String message) {
        return new Result(status, Map.of("error", message == null ? "" : message));
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        return node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
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
