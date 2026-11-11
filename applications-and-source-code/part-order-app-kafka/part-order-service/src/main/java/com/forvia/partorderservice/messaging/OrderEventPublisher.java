package com.forvia.partorderservice.messaging;

import com.forvia.partorderservice.event.OrderPlacedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderEventPublisher {

    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;
    private final String topic;

    public OrderEventPublisher(KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate,
                               @Value("${app.kafka.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(OrderPlacedEvent event) {
        // key = SKU: all orders for one part go to one partition, so the
        // inventory service sees them in the order they were placed
        kafkaTemplate.send(topic, event.sku(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {}", event, ex);
                    } else {
                        log.info("Published {} to {}-{}@{}", event.orderNumber(),
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
