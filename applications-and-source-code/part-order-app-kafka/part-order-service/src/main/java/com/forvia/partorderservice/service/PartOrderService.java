package com.forvia.partorderservice.service;

import com.forvia.partorderservice.dto.OrderRequestDto;
import com.forvia.partorderservice.event.OrderPlacedEvent;
import com.forvia.partorderservice.messaging.OrderEventPublisher;
import com.forvia.partorderservice.model.PartOrder;
import com.forvia.partorderservice.repository.PartOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartOrderService {

    private final PartOrderRepository partOrderRepository;
    private final OrderEventPublisher orderEventPublisher;

    /**
     * Save the order locally, then fire-and-forget an event. Unlike the REST
     * version, this does NOT wait for part-inventory-service (it doesn't even
     * need to be running) - the order is "PLACED", not "confirmed".
     */
    public PartOrder placeOrder(OrderRequestDto request) {
        PartOrder order = new PartOrder();
        order.setId(UUID.randomUUID().toString());
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setPartSku(request.sku());
        order.setQuantity(request.quantity());
        order.setStatus("PLACED");
        order.setTimestamp(LocalDateTime.now());
        partOrderRepository.save(order);

        orderEventPublisher.publish(new OrderPlacedEvent(
                order.getOrderNumber(), order.getPartSku(), order.getQuantity(), order.getTimestamp()));
        return order;
    }

    public List<PartOrder> getAllOrders() {
        return partOrderRepository.findAll();
    }
}
