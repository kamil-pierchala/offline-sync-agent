package com.pierchala.syncserver.service;

import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;
import com.pierchala.syncserver.entity.OrderEntity;
import com.pierchala.syncserver.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class SyncService {

    private final OrderRepository orderRepository;

    public SyncService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public SyncBatchResponse processBatch(SyncBatchRequest request) {
        List<SyncBatchResponse.SyncedItem> results = new ArrayList<>();

        for (SyncBatchRequest.OrderSyncPayload payload : request.items()) {
            OrderDto dto = payload.order();
            try {
                switch (payload.syncStatus()) {
                    case DIRTY_CREATED -> {
                        OrderEntity entity = new OrderEntity(
                                dto.id(),
                                dto.orderNumber(),
                                dto.customerName(),
                                dto.totalAmount(),
                                dto.status(),
                                Instant.now(),
                                1L
                        );
                        orderRepository.save(entity);
                        results.add(new SyncBatchResponse.SyncedItem(dto.id(), 1L, true, null));
                    }
                    case DIRTY_UPDATED -> {
                        OrderEntity entity = orderRepository.findById(dto.id())
                                .orElse(new OrderEntity(dto.id(), dto.orderNumber(), dto.customerName(), dto.totalAmount(), dto.status(), Instant.now(), 0L));

                        entity.setOrderNumber(dto.orderNumber());
                        entity.setCustomerName(dto.customerName());
                        entity.setTotalAmount(dto.totalAmount());
                        entity.setStatus(dto.status());
                        entity.setUpdatedAt(Instant.now());
                        entity.setVersion(entity.getVersion() + 1);

                        orderRepository.save(entity);
                        results.add(new SyncBatchResponse.SyncedItem(dto.id(), entity.getVersion(), true, null));
                    }
                    case DIRTY_DELETED -> {
                        orderRepository.deleteById(dto.id());
                        results.add(new SyncBatchResponse.SyncedItem(dto.id(), null, true, null));
                    }
                    default -> {}
                }
            } catch (Exception ex) {
                results.add(new SyncBatchResponse.SyncedItem(dto.id(), null, false, ex.getMessage()));
            }
        }

        return new SyncBatchResponse(results);
    }
}