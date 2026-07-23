package com.forvia.partinventoryservice.dto;

public record OrderResponseDto(String partSku, String status, int quantity, double totalPrice) {
}
