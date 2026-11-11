package com.forvia.partorderservice.api;

import com.forvia.partorderservice.dto.OrderRequestDto;
import com.forvia.partorderservice.model.PartOrder;
import com.forvia.partorderservice.service.PartOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class PartOrderController {

    private final PartOrderService partOrderService;

    // 202 Accepted, not 200: the order is recorded and the event is queued,
    // but stock hasn't been checked yet - that happens asynchronously
    @PostMapping
    public ResponseEntity<PartOrder> placeOrder(@RequestBody OrderRequestDto request) {
        if (request.sku() == null || request.sku().isBlank()
                || request.quantity() == null || request.quantity() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(partOrderService.placeOrder(request));
    }

    @GetMapping
    public List<PartOrder> getAllOrders() {
        return partOrderService.getAllOrders();
    }
}
