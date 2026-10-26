package com.pierchala.syncserver.repository;

import com.pierchala.syncserver.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    List<OrderEntity> findByUpdatedAtAfterOrderByUpdatedAtAsc(Instant since);
}