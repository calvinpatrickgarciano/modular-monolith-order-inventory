package edu.cit.garciano.shop.event;

public record OrderRejectedEvent(
        Long orderId,
        String reason
) {
}