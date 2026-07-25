package com.forvia.partorderservice.dto;

public record OrderResponseDto(String partSku, String status, int quantity, double totalPrice) {
}
