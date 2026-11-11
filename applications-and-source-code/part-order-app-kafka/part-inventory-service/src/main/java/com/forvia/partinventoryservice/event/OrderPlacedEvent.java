package com.forvia.partinventoryservice.event;

import java.time.LocalDateTime;

/**
 * Consumer-side copy of part-order-service's OrderPlacedEvent. Only the JSON
 * field names have to match - the package/class name doesn't (see
 * spring.json.value.default.type in application.yml).
 */
public record OrderPlacedEvent(String orderNumber, String sku, int quantity, LocalDateTime placedAt) {
}
