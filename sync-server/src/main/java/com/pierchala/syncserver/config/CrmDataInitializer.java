package com.pierchala.syncserver.config;

import com.pierchala.syncserver.entity.CustomerEntity;
import com.pierchala.syncserver.repository.CustomerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class CrmDataInitializer implements CommandLineRunner {

    private final CustomerRepository customerRepository;

    public CrmDataInitializer(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public void run(String... args) {
        if (customerRepository.count() == 0) {
            Instant now = Instant.now();
            List<CustomerEntity> initialCustomers = List.of(
                    new CustomerEntity(UUID.randomUUID(), "TechNova Sp. z o.o.", "PL5250001122", "zakupy@technova.pl", "Warszawa", now),
                    new CustomerEntity(UUID.randomUUID(), "LogiTrans S.A.", "PL8971234567", "biuro@logitrans.pl", "Wrocław", now),
                    new CustomerEntity(UUID.randomUUID(), "SoftHouse Polska", "PL6769876543", "kontakt@softhouse.pl", "Kraków", now)
            );
            customerRepository.saveAll(initialCustomers);
        }
    }
}