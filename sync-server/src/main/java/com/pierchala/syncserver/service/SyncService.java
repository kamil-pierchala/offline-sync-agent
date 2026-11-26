package com.pierchala.syncserver.service;

import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;
import com.pierchala.synccore.model.SyncPullResponse;
import com.pierchala.syncserver.entity.OrderEntity;
import com.pierchala.syncserver.repository.OrderRepository;
import com.pierchala.synccore.model.OrderItemDto;
import com.pierchala.syncserver.entity.OrderItemEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SyncService {

    private final OrderRepository orderRepository;
    private final com.pierchala.syncserver.repository.ProductRepository productRepository;

    public SyncService(OrderRepository orderRepository,
                       com.pierchala.syncserver.repository.ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
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
                        // Add items if provided
                        if (dto.items() != null) {
                            for (OrderItemDto itemDto : dto.items()) {
                                entity.addItem(new OrderItemEntity(
                                        itemDto.id(),
                                        itemDto.productName(),
                                        itemDto.quantity(),
                                        itemDto.unitPrice(),
                                        entity
                                ));
                            }
                        }
                        deductStockForItems(dto.items());
                        orderRepository.save(entity);
                        results.add(new SyncBatchResponse.SyncedItem(dto.id(), 1L, true, null));
                    }
                    case DIRTY_UPDATED -> {
                        Optional<OrderEntity> existingOpt = orderRepository.findById(dto.id());

                        if (existingOpt.isPresent()) {
                            OrderEntity entity = existingOpt.get();

                            // If server has a newer version than the client -> CONFLICT
                            if (entity.getVersion() > dto.version()) {
                                results.add(new SyncBatchResponse.SyncedItem(
                                        dto.id(),
                                        entity.getVersion(),
                                        false,
                                        "CONFLICT"
                                ));
                                continue;
                            }

                            // When versions match, save changes and increment version
                            entity.setOrderNumber(dto.orderNumber());
                            entity.setCustomerName(dto.customerName());
                            entity.setTotalAmount(dto.totalAmount());
                            entity.setStatus(dto.status());
                            entity.setUpdatedAt(Instant.now());
                            entity.setVersion(entity.getVersion() + 1);

                            // Adjust inventory stock based on item quantity delta
                            adjustStockOnUpdate(entity.getItems(), dto.items());

                            // Update order items
                            entity.clearItems();
                                if (dto.items() != null) {
                                    for (OrderItemDto itemDto : dto.items()) {
                                        entity.addItem(new OrderItemEntity(
                                                itemDto.id(),
                                                itemDto.productName(),
                                                itemDto.quantity(),
                                                itemDto.unitPrice(),
                                                entity
                                        ));
                                    }
                                }

                            orderRepository.save(entity);
                            results.add(new SyncBatchResponse.SyncedItem(dto.id(), entity.getVersion(), true, null));
                        } else {
                            OrderEntity newEntity = new OrderEntity(
                                    dto.id(),
                                    dto.orderNumber(),
                                    dto.customerName(),
                                    dto.totalAmount(),
                                    dto.status(),
                                    Instant.now(),
                                    1L
                            );
                            if (dto.items() != null) {
                                for (OrderItemDto itemDto: dto.items()) {
                                    newEntity.addItem(new OrderItemEntity(
                                            itemDto.id(),
                                            itemDto.productName(),
                                            itemDto.quantity(),
                                            itemDto.unitPrice(),
                                            newEntity
                                    ));
                                }
                            }
                            orderRepository.save(newEntity);
                            results.add(new SyncBatchResponse.SyncedItem(dto.id(), 1L, true, null));
                        }
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

    @Transactional(readOnly = true)
    public SyncPullResponse fetchUpdatesSince(Instant since) {
        Instant fetchTime = Instant.now();
        Instant querySince = (since != null) ? since : Instant.EPOCH;

        List<OrderDto> dtos = orderRepository.findByUpdatedAtAfterOrderByUpdatedAtAsc(querySince)
                .stream()
                .map(entity -> {
                    List<OrderItemDto> itemDtos = entity.getItems().stream()
                            .map(item -> new OrderItemDto(
                                    item.getId(),
                                    item.getProductName(),
                                    item.getQuantity(),
                                    item.getUnitPrice()
                            ))
                            .toList();

                    return new OrderDto(
                            entity.getId(),
                            entity.getOrderNumber(),
                            entity.getCustomerName(),
                            entity.getTotalAmount(),
                            entity.getStatus(),
                            entity.getUpdatedAt(),
                            entity.getVersion(),
                            itemDtos
                    );
                })
                .toList();

        return new SyncPullResponse(dtos, fetchTime);
    }

    private void deductStockForItems(List<com.pierchala.synccore.model.OrderItemDto> items) {
        if (items == null) return;
        for (var item : items) {
            String rawName = item.productName();
            if (rawName != null && rawName.contains(" - ")) {
                String productCode = rawName.split(" - ", 2)[0].trim();
                productRepository.findByProductCode(productCode).ifPresent(product -> {
                    int updatedStock = Math.max(0, product.getStockQuantity() - item.quantity());
                    product.setStockQuantity(updatedStock);
                    product.setUpdatedAt(Instant.now());
                    productRepository.save(product);
                });
            }
        }
    }

    private void adjustStockOnUpdate(List<OrderItemEntity> oldItems, List<OrderItemDto> newItems) {
        java.util.Map<String, Integer> oldQtyByCode = new java.util.HashMap<>();
        if (oldItems != null) {
            for (OrderItemEntity old : oldItems) {
                if (old.getProductName() != null && old.getProductName().contains(" - ")) {
                    String code = old.getProductName().split(" - ", 2)[0].trim();
                    oldQtyByCode.merge(code, old.getQuantity(), Integer::sum);
                }
            }
        }

        java.util.Map<String, Integer> newQtyByCode = new java.util.HashMap<>();
        if (newItems != null) {
            for (OrderItemDto item : newItems) {
                if (item.productName() != null && item.productName().contains(" - ")) {
                    String code = item.productName().split(" - ", 2)[0].trim();
                    newQtyByCode.merge(code, item.quantity(), Integer::sum);
                }
            }
        }

        java.util.Set<String> allCodes = new java.util.HashSet<>();
        allCodes.addAll(oldQtyByCode.keySet());
        allCodes.addAll(newQtyByCode.keySet());

        for (String code : allCodes) {
            int delta = newQtyByCode.getOrDefault(code, 0) - oldQtyByCode.getOrDefault(code, 0);
            if (delta != 0) {
                productRepository.findByProductCode(code).ifPresent(product -> {
                    int updatedStock = Math.max(0, product.getStockQuantity() - delta);
                    product.setStockQuantity(updatedStock);
                    product.setUpdatedAt(Instant.now());
                    productRepository.save(product);
                });
            }
        }
    }
}