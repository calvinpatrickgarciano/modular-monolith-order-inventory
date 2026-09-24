package edu.cit.garciano.supplier;

public record SupplierOrderDeliveredEvent(
        Long supplierOrderId,
        String productId,
        int unitsDelivered
) {}