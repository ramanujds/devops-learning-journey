package com.forvia.partinventoryservice.api;

import com.forvia.partinventoryservice.model.Part;
import com.forvia.partinventoryservice.repository.PartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only: lets you watch stock go down as orders are consumed. */
@RestController
@RequestMapping("/api/parts")
@RequiredArgsConstructor
public class PartController {

    private final PartRepository partRepository;

    @GetMapping
    public List<Part> getAllParts() {
        return partRepository.findAll();
    }

    @GetMapping("/{sku}")
    public ResponseEntity<Part> getPartBySku(@PathVariable String sku) {
        return partRepository.findBySku(sku).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
