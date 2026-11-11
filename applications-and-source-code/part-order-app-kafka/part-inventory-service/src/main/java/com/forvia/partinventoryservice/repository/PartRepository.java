package com.forvia.partinventoryservice.repository;

import com.forvia.partinventoryservice.model.Part;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PartRepository extends JpaRepository<Part, String> {

    Optional<Part> findBySku(String sku);

}
