package edu.cit.garciano.shop;

public record PlaceOrderRequest(
        String productId,
        int quantity
) {
}