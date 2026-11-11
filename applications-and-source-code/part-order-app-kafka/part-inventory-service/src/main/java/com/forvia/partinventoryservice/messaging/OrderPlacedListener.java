package com.forvia.partinventoryservice.messaging;

import com.forvia.partinventoryservice.event.OrderPlacedEvent;
import com.forvia.partinventoryservice.model.Part;
import com.forvia.partinventoryservice.repository.PartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the REST version's POST /api/parts/place-order endpoint: instead
 * of part-order-service calling us, we pull OrderPlacedEvents off the topic.
 */
@Component
@Slf4j
public class OrderPlacedListener {

    private final PartRepository partRepository;
    private final long processingDelayMs;

    public OrderPlacedListener(PartRepository partRepository,
                               @Value("${app.processing-delay-ms}") long processingDelayMs) {
        this.partRepository = partRepository;
        this.processingDelayMs = processingDelayMs;
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    @Transactional
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("Received {}", event);
        simulateWork();

        Part part = partRepository.findBySku(event.sku()).orElse(null);
        if (part == null) {
            // nobody to tell: this is one-way messaging, so a rejected order
            // only shows up here in the logs (see NOTES.md "Next steps")
            log.warn("Order {} rejected: unknown SKU {}", event.orderNumber(), event.sku());
            return;
        }
        if (part.getStock() < event.quantity()) {
            log.warn("Order {} rejected: insufficient stock for {} (have {}, need {})",
                    event.orderNumber(), event.sku(), part.getStock(), event.quantity());
            return;
        }

        part.setStock(part.getStock() - event.quantity());
        partRepository.save(part);
        log.info("Order {} reserved {} x {}, stock now {}",
                event.orderNumber(), event.quantity(), event.sku(), part.getStock());
    }

    /** Stand-in for slow real work (calling a supplier, etc.) - see app.processing-delay-ms. */
    private void simulateWork() {
        if (processingDelayMs <= 0) return;
        try {
            Thread.sleep(processingDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
