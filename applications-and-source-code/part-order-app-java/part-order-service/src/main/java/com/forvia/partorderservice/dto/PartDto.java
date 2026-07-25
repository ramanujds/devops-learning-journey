package com.forvia.partorderservice.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PartDto {
    private String sku;
    private String name;
    private BigDecimal price;
    private Integer stock;
}
