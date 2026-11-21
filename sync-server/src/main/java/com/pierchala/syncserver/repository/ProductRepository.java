package com.pierchala.syncserver.repository;

import com.pierchala.syncserver.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    Optional<ProductEntity> findByProductCode(String productCode);
    List<ProductEntity> findByUpdatedAtAfterOrderByUpdatedAtAsc(Instant since);
}