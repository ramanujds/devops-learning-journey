package com.forvia.partorderservice.client;

import com.forvia.partorderservice.dto.OrderRequestDto;
import com.forvia.partorderservice.dto.OrderResponseDto;
import com.forvia.partorderservice.dto.PartDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "inventory-service",url = "${INVENTORY_SERVICE_URL:http://localhost:8080}")
public interface InventoryServiceClient {

    @GetMapping("/api/parts")
    List<PartDto> getAllParts();

    @PostMapping("/api/parts/place-order")
    OrderResponseDto placeOrder(@RequestBody OrderRequestDto orderRequest);
}