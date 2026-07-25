package com.forvia.partorderservice.api;

import com.forvia.partorderservice.dto.OrderRequestDto;
import com.forvia.partorderservice.dto.OrderResponseDto;
import com.forvia.partorderservice.dto.PartDto;
import com.forvia.partorderservice.model.PartOrder;
import com.forvia.partorderservice.repository.PartOrderRepository;
import com.forvia.partorderservice.service.PartOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/part-orders")
public class PartOrderController {

    @Autowired
    private PartOrderRepository partOrderRepository;

    @Autowired
    private PartOrderService partOrderService;

    @PostMapping("/place-order")
    public ResponseEntity<OrderResponseDto> placeOrder(@RequestBody OrderRequestDto orderRequest) {
        OrderResponseDto response = partOrderService.placeOrder(orderRequest);
        if ("Order placed successfully".equals(response.status())) {
            // Optionally save the order locally
            PartOrder partOrder = new PartOrder();
            partOrder.setId(UUID.randomUUID().toString());
            partOrder.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            partOrder.setPartSku(response.partSku());
            partOrder.setQuantity(response.quantity());
            partOrder.setStatus("PLACED");
            partOrder.setTimestamp(LocalDateTime.now());
            partOrder.setPrice(BigDecimal.valueOf(response.totalPrice()));
            // Set price if available from PartDto, but for simplicity, omit or fetch separately
            partOrderRepository.save(partOrder);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/available-parts")
    public ResponseEntity<List<PartDto>> getAllAvailableParts(){
        List<PartDto> parts = partOrderService.getAllAvailableParts();
        return ResponseEntity.ok(parts);
    }

    @PostMapping
    public ResponseEntity<PartOrder> createPartOrder(@RequestBody PartOrder partOrder) {
        partOrder.setId(UUID.randomUUID().toString());
        PartOrder savedPartOrder = partOrderRepository.save(partOrder);
        return ResponseEntity.ok(savedPartOrder);
    }

    @GetMapping
    public List<PartOrder> getAllPartOrders() {
        return partOrderRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PartOrder> getPartOrderById(@PathVariable String id) {
        Optional<PartOrder> partOrder = partOrderRepository.findById(id);
        return partOrder.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/sku/{partSku}")
    public List<PartOrder> getPartOrdersBySku(@PathVariable String partSku) {
        return partOrderRepository.findByPartSku(partSku);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PartOrder> updatePartOrder(@PathVariable String id, @RequestBody PartOrder partOrderDetails) {
        Optional<PartOrder> optionalPartOrder = partOrderRepository.findById(id);
        if (optionalPartOrder.isPresent()) {
            PartOrder partOrder = optionalPartOrder.get();
            partOrder.setOrderNumber(partOrderDetails.getOrderNumber());
            partOrder.setPartSku(partOrderDetails.getPartSku());
            partOrder.setPrice(partOrderDetails.getPrice());
            partOrder.setQuantity(partOrderDetails.getQuantity());
            partOrder.setStatus(partOrderDetails.getStatus());
            partOrder.setTimestamp(partOrderDetails.getTimestamp());
            PartOrder updatedPartOrder = partOrderRepository.save(partOrder);
            return ResponseEntity.ok(updatedPartOrder);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePartOrder(@PathVariable String id) {
        if (partOrderRepository.existsById(id)) {
            partOrderRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}