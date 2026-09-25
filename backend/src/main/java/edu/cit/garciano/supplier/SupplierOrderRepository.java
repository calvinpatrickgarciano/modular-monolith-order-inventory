package edu.cit.garciano.supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
interface SupplierOrderRepository
       extends JpaRepository<SupplierOrder, Long> {
   List<SupplierOrder> findByStatus(
           SupplierOrderStatus status
   );
   List<SupplierOrder> findByStatusIn(
           Collection<SupplierOrderStatus> statuses
   );
   Optional<SupplierOrder>
   findFirstByProductIdAndStatusInOrderByCreatedAtDesc(
           String productId,
           Collection<SupplierOrderStatus> statuses
   );
}