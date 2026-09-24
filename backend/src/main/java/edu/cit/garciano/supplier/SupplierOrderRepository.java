package edu.cit.garciano.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface SupplierOrderRepository
        extends JpaRepository<SupplierOrder, Long> {

    List<SupplierOrder> findByStatus(
            SupplierOrderStatus status
    );

    List<SupplierOrder> findByStatusIn(
            Collection<SupplierOrderStatus> statuses
    );
}