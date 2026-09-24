package edu.cit.garciano.supplier;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "supplier_orders")
class SupplierOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(name = "buyer_ref", unique = true, length = 40)
    private String buyerRef;

    @Column(
            name = "request_id",
            nullable = false,
            unique = true,
            length = 80
    )
    private String requestId;

    @Column(name = "po_number")
    private String poNumber;

    private Integer cases;

    @Column(nullable = false)
    private int units;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierOrderStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SupplierOrder() {
    }

    SupplierOrder(
            String productId,
            String requestId,
            int cases,
            int units
    ) {
        this.productId = productId;
        this.requestId = requestId;
        this.cases = cases;
        this.units = units;
        this.status = SupplierOrderStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = OffsetDateTime.now();
    }

    Long getId() {
        return id;
    }

    String getProductId() {
        return productId;
    }

    String getBuyerRef() {
        return buyerRef;
    }

    String getRequestId() {
        return requestId;
    }

    String getPoNumber() {
        return poNumber;
    }

    Integer getCases() {
        return cases;
    }

    int getUnits() {
        return units;
    }

    SupplierOrderStatus getStatus() {
        return status;
    }

    void setBuyerRef(String buyerRef) {
        this.buyerRef = buyerRef;
    }

    void setPoNumber(String poNumber) {
        this.poNumber = poNumber;
    }

    void setStatus(SupplierOrderStatus status) {
        this.status = status;
    }
}