package com.pierchala.syncserver.controller;

import com.pierchala.synccore.model.ProductDto;
import com.pierchala.syncserver.entity.ProductEntity;
import com.pierchala.syncserver.repository.ProductRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory/products")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping
    public ResponseEntity<List<ProductDto>> getAllProducts() {
        List<ProductDto> products = productRepository.findAll().stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(products);
    }

    @GetMapping("/sync")
    public ResponseEntity<List<ProductDto>> pullProductsSince(
            @RequestParam(name = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant since) {
        Instant querySince = (since != null) ? since : Instant.EPOCH;
        List<ProductDto> products = productRepository.findByUpdatedAtAfterOrderByUpdatedAtAsc(querySince).stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(products);
    }

    @PostMapping
    public ResponseEntity<ProductDto> createProduct(@RequestBody ProductDto dto) {
        UUID id = (dto.id() != null) ? dto.id() : UUID.randomUUID();
        ProductEntity entity = new ProductEntity(
                id,
                dto.productCode(),
                dto.name(),
                dto.unitPrice(),
                dto.stockQuantity(),
                Instant.now()
        );
        ProductEntity saved = productRepository.save(entity);
        return ResponseEntity.ok(toDto(saved));
    }

    private ProductDto toDto(ProductEntity entity) {
        return new ProductDto(
                entity.getId(),
                entity.getProductCode(),
                entity.getName(),
                entity.getUnitPrice(),
                entity.getStockQuantity(),
                entity.getUpdatedAt()
        );
    }
}