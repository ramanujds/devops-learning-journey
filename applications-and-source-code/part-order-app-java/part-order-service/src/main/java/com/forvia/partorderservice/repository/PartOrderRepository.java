package com.forvia.partorderservice.repository;
import com.forvia.partorderservice.model.PartOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartOrderRepository extends JpaRepository<PartOrder, String> {
    List<PartOrder> findByPartSku(String partSku);
}
