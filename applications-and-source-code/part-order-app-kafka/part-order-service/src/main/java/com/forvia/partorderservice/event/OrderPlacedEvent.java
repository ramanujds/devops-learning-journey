package com.forvia.partorderservice.event;

import java.time.LocalDateTime;

/**
 * The one message this app sends. Serialized to JSON and published to the
 * "part-orders" topic, e.g.
 * {"orderNumber":"ORD-1A2B3C4D","sku":"SEAT-SAFE45-001","quantity":2,"placedAt":"..."}
 *
 * part-inventory-service has its own copy of this record (same field names) -
 * the two services share a JSON contract, not a Java class.
 */
public record OrderPlacedEvent(String orderNumber, String sku, int quantity, LocalDateTime placedAt) {
}
