package edu.cit.garciano.supplier;

public record SupplierOrderResult(
        Long supplierOrderId,
        String productId,
        int unitsOrdered,
        SupplierOrderStatus status,
        String message
) {}