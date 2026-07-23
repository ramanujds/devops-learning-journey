package com.forvia.partorderservice.service;

import com.forvia.partorderservice.client.InventoryServiceClient;
import com.forvia.partorderservice.dto.OrderRequestDto;
import com.forvia.partorderservice.dto.OrderResponseDto;
import com.forvia.partorderservice.dto.PartDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PartOrderService {

    @Autowired
    private InventoryServiceClient inventoryServiceClient;

    public List<PartDto> getAllAvailableParts() {
        return inventoryServiceClient.getAllParts();
    }

    public OrderResponseDto placeOrder(OrderRequestDto orderRequest) {
        return inventoryServiceClient.placeOrder(orderRequest);
    }
}