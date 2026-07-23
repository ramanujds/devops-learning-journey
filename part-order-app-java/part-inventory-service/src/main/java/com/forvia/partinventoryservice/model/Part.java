package com.forvia.partinventoryservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Entity
@Data
@Table(name = "parts")
public class Part {

    @Id
    private String id;
    private String sku;
    private String name;
    private BigDecimal price;
    private Integer stock;

}
