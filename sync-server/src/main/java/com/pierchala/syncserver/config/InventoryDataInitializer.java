package com.pierchala.syncserver.config;

import com.pierchala.syncserver.entity.ProductEntity;
import com.pierchala.syncserver.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class InventoryDataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;

    public InventoryDataInitializer(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (productRepository.count() == 0) {
            Instant now = Instant.now();
            List<ProductEntity> initialProducts = List.of(
                    new ProductEntity(UUID.randomUUID(), "LAP-DELL-XPS15", "Laptop Dell XPS 15", new BigDecimal("7499.00"), 12, now),
                    new ProductEntity(UUID.randomUUID(), "MON-LG-27UL", "Monitor LG 27 4K", new BigDecimal("1650.00"), 25, now),
                    new ProductEntity(UUID.randomUUID(), "KBD-LOGI-MX", "Logitech MX Keys", new BigDecimal("489.00"), 40, now),
                    new ProductEntity(UUID.randomUUID(), "DOC-USB-C", "Stacja dokująca USB-C", new BigDecimal("620.00"), 18, now)
            );
            productRepository.saveAll(initialProducts);
        }
    }
}