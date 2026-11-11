package com.forvia.partorderservice.repository;

import com.forvia.partorderservice.model.PartOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartOrderRepository extends JpaRepository<PartOrder, String> {
}
