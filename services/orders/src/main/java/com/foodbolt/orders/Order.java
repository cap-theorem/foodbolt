package com.foodbolt.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class Order {
    static final List<String> FLOW =
            List.of("placing", "confirmed", "preparing", "ready_for_pickup", "out_for_delivery", "delivered");
    static final Set<String> CANCELLABLE = Set.of("confirmed", "preparing", "ready_for_pickup");
    static final Set<String> TERMINAL = Set.of("cancelled", "payment_failed");

    record Entry(Instant at, String event, String source, String detail) {}

    record EventOutcome(boolean accepted, String status, boolean becameDelivered) {}

    record Payment(String paymentId, long amountCents) {}

    final String orderId;
    final String customerId;
    final String requestId;
    final Instant placedAt;

    private String status = "placing";
    private Instant updatedAt;
    private JsonNode restaurant;
    private JsonNode lines;
    private long subtotalCents;
    private long deliveryFeeCents;
    private long serviceFeeCents;
    private long taxCents;
    private long tipCents;
    private long totalCents;
    private JsonNode dropoff;
    private String paymentId;
    private String paymentMethod;
    private String paymentStatus;
    private String ticketId;
    private String deliveryId;
    private JsonNode courier;
    private long etaSeconds;
    private boolean foodReady;
    private boolean readyForwarded;
    private final List<Entry> timeline = new ArrayList<>();

    Order(String orderId, String customerId, String requestId, Instant placedAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.requestId = requestId;
        this.placedAt = placedAt;
        this.updatedAt = placedAt;
    }

    synchronized void applyQuote(JsonNode quote, JsonNode dropoff, String paymentMethod, long tipCents) {
        this.restaurant = quote.path("restaurant").deepCopy();
        this.lines = quote.path("lines").deepCopy();
        this.subtotalCents = quote.path("subtotal_cents").asLong();
        this.deliveryFeeCents = quote.path("delivery_fee_cents").asLong();
        this.serviceFeeCents = quote.path("service_fee_cents").asLong();
        this.taxCents = quote.path("tax_cents").asLong();
        this.tipCents = tipCents;
        this.totalCents = quote.path("total_cents").asLong() + tipCents;
        this.dropoff = dropoff.deepCopy();
        this.paymentMethod = paymentMethod;
    }

    synchronized long totalCents() {
        return totalCents;
    }

    synchronized String status() {
        return status;
    }

    synchronized String paymentId() {
        return paymentId;
    }

    synchronized String ticketId() {
        return ticketId;
    }

    synchronized String deliveryId() {
        return deliveryId;
    }

    synchronized Payment payment() {
        return paymentId == null ? null : new Payment(paymentId, totalCents);
    }

    synchronized void paymentAuthorized(String paymentId) {
        this.paymentId = paymentId;
        this.paymentStatus = "authorized";
        this.updatedAt = Instant.now();
    }

    synchronized void paymentFailed(String declineCode) {
        this.paymentStatus = "declined";
        this.status = "payment_failed";
        record("payment_failed", "payments", declineCode);
    }

    synchronized void paymentVoided() {
        this.paymentStatus = "voided";
        this.updatedAt = Instant.now();
    }

    synchronized void paymentCaptured() {
        this.paymentStatus = "captured";
        record("payment_captured", "payments", String.format("$%d.%02d captured", totalCents / 100, totalCents % 100));
    }

    synchronized void ticketAccepted(String ticketId) {
        this.ticketId = ticketId;
        this.updatedAt = Instant.now();
    }

    synchronized void courierAssigned(String deliveryId, JsonNode courier, long etaSeconds) {
        this.deliveryId = deliveryId;
        this.courier = courier.deepCopy();
        this.etaSeconds = etaSeconds;
        this.updatedAt = Instant.now();
    }

    synchronized void confirm(String detail) {
        if (rank(status) < rank("confirmed")) {
            status = "confirmed";
        }
        record("confirmed", "orders", detail);
    }

    synchronized void cancel(String source, String detail) {
        status = "cancelled";
        record("cancelled", source, detail);
    }

    synchronized boolean cancelIfAllowed(String detail) {
        if (!CANCELLABLE.contains(status)) {
            return false;
        }
        cancel("orders", detail);
        return true;
    }

    synchronized EventOutcome applyEvent(String event, String source, String detail, String target) {
        if (TERMINAL.contains(status)) {
            return new EventOutcome(false, status, false);
        }
        boolean becameDelivered = false;
        if (target != null && rank(target) > rank(status)) {
            becameDelivered = target.equals("delivered");
            status = target;
        }
        record(event, source, detail);
        if (event.equals("food_ready")) {
            foodReady = true;
        }
        return new EventOutcome(true, status, becameDelivered);
    }

    synchronized String takeReadyForward() {
        if (!foodReady || deliveryId == null || readyForwarded || TERMINAL.contains(status)) {
            return null;
        }
        readyForwarded = true;
        return deliveryId;
    }

    synchronized ObjectNode toJson(ObjectMapper json) {
        ObjectNode out = json.createObjectNode();
        out.put("order_id", orderId);
        out.put("customer_id", customerId);
        out.put("status", status);
        out.set("restaurant", restaurant == null ? null : restaurant.deepCopy());
        out.set("lines", lines == null ? json.createArrayNode() : lines.deepCopy());
        out.put("subtotal_cents", subtotalCents);
        out.put("delivery_fee_cents", deliveryFeeCents);
        out.put("service_fee_cents", serviceFeeCents);
        out.put("tax_cents", taxCents);
        out.put("tip_cents", tipCents);
        out.put("total_cents", totalCents);
        out.set("dropoff", dropoff == null ? null : dropoff.deepCopy());

        if (paymentMethod == null) {
            out.putNull("payment");
        } else {
            ObjectNode payment = out.putObject("payment");
            payment.put("payment_id", paymentId);
            payment.put("method", paymentMethod);
            payment.put("status", paymentStatus);
        }

        out.put("ticket_id", ticketId);

        if (deliveryId == null) {
            out.putNull("delivery");
        } else {
            ObjectNode delivery = out.putObject("delivery");
            delivery.put("delivery_id", deliveryId);
            delivery.set("courier", courier == null ? null : courier.deepCopy());
            delivery.put("eta_seconds", etaSeconds);
        }

        out.put("placed_at", Log.iso(placedAt));
        out.put("updated_at", Log.iso(updatedAt));
        ArrayNode entries = out.putArray("timeline");
        for (Entry e : timeline) {
            ObjectNode entry = entries.addObject();
            entry.put("at", Log.iso(e.at()));
            entry.put("event", e.event());
            entry.put("source", e.source());
            entry.put("detail", e.detail());
        }
        return out;
    }

    static int rank(String status) {
        return FLOW.indexOf(status);
    }

    private void record(String event, String source, String detail) {
        Instant now = Instant.now();
        timeline.add(new Entry(now, event, source, detail));
        updatedAt = now;
    }
}
