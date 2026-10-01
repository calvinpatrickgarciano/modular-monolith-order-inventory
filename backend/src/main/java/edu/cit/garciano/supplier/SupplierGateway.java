package edu.cit.garciano.supplier;

public interface SupplierGateway {

    SupplierOrderResult reorder(
            String productId,
            int unitsNeeded
    );

    boolean hasOpenReorder(
            String productId
    );
}