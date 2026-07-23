package com.forvia.partinventoryservice.api;

import com.forvia.partinventoryservice.dto.OrderRequestDto;
import com.forvia.partinventoryservice.dto.OrderResponseDto;
import com.forvia.partinventoryservice.model.Part;
import com.forvia.partinventoryservice.repository.PartRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/parts")
public class PartController {

    @Autowired
    private PartRepository partRepository;

    // Create a new Part
    @PostMapping
    public ResponseEntity<Part> createPart(@RequestBody Part part) {
        part.setId(UUID.randomUUID().toString());
        Part savedPart = partRepository.save(part);
        return ResponseEntity.ok(savedPart);
    }

    // Get all Parts
    @GetMapping
    public List<Part> getAllParts() {
        return partRepository.findAll();
    }

    // Get Part by ID
    @GetMapping("/{id}")
    public ResponseEntity<Part> getPartById(@PathVariable String id) {
        Optional<Part> part = partRepository.findById(id);
        return part.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // Get Parts by SKU
    @GetMapping("/sku/{sku}")
    public List<Part> getPartsBySku(@PathVariable String sku) {
        return partRepository.findBySku(sku);
    }

    // Update an existing Part
    @PutMapping("/{id}")
    public ResponseEntity<Part> updatePart(@PathVariable String id, @RequestBody Part partDetails) {
        Optional<Part> optionalPart = partRepository.findById(id);
        if (optionalPart.isPresent()) {
            Part part = optionalPart.get();
            part.setSku(partDetails.getSku());
            part.setName(partDetails.getName());
            part.setPrice(partDetails.getPrice());
            part.setStock(partDetails.getStock());
            Part updatedPart = partRepository.save(part);
            return ResponseEntity.ok(updatedPart);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // Delete a Part by ID
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePart(@PathVariable String id) {
        if (partRepository.existsById(id)) {
            partRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }


    @PostMapping("/place-order")
    public ResponseEntity<OrderResponseDto> placeOrder(@RequestBody OrderRequestDto orderRequest) {
        List<Part> parts = partRepository.findBySku(orderRequest.sku());
        if (parts.isEmpty()) {
            return ResponseEntity.badRequest().body(new OrderResponseDto("", "Part not found",0,0));
        }
        Part part = parts.get(0);
        if (part.getStock() < orderRequest.quantity()) {
            return ResponseEntity.badRequest().body(new OrderResponseDto("", "Insufficient stock",0,0));
        }
        part.setStock(part.getStock() - orderRequest.quantity());
        partRepository.save(part);
        return ResponseEntity.ok(new OrderResponseDto(part.getSku(), "Order placed successfully", orderRequest.quantity(), part.getPrice().doubleValue() * orderRequest.quantity()));
    }


}