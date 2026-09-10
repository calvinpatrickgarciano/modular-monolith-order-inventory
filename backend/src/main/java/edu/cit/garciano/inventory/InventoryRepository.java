package edu.cit.garciano.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface InventoryRepository extends JpaRepository<InventoryItem, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT i
        FROM InventoryItem i
        WHERE i.productId = :productId
    """)
    Optional<InventoryItem> findForUpdate(
            @Param("productId") String productId
    );
}