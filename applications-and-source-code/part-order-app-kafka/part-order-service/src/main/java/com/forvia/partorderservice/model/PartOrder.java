package com.forvia.partorderservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "part_orders")
@Data
public class PartOrder {
    @Id
    private String id;
    private String orderNumber;
    private String partSku;
    private Integer quantity;
    private String status;
    private LocalDateTime timestamp;
}
