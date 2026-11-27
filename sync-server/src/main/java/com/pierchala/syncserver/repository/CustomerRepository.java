package com.pierchala.syncserver.repository;

import com.pierchala.syncserver.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {
    Optional<CustomerEntity> findByTaxId(String taxId);
    List<CustomerEntity> findByUpdatedAtAfterOrderByUpdatedAtAsc(Instant since);
}