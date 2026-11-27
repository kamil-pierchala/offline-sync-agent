package com.pierchala.syncserver.controller;

import com.pierchala.synccore.model.CustomerDto;
import com.pierchala.syncserver.entity.CustomerEntity;
import com.pierchala.syncserver.repository.CustomerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/crm/customers")
public class CustomerController {

    private final CustomerRepository customerRepository;

    public CustomerController(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @GetMapping
    public ResponseEntity<List<CustomerDto>> getAllCustomers() {
        List<CustomerDto> customers = customerRepository.findAll().stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(customers);
    }

    @PostMapping
    public ResponseEntity<CustomerDto> createOrUpdateCustomer(@RequestBody CustomerDto dto) {
        UUID id = (dto.id() != null) ? dto.id() : UUID.randomUUID();
        CustomerEntity entity = customerRepository.findById(id)
                .orElse(new CustomerEntity(id, dto.companyName(), dto.taxId(), dto.email(), dto.city(), Instant.now()));

        entity.setCompanyName(dto.companyName());
        entity.setTaxId(dto.taxId());
        entity.setEmail(dto.email());
        entity.setCity(dto.city());
        entity.setUpdatedAt(Instant.now());

        CustomerEntity saved = customerRepository.save(entity);
        return ResponseEntity.ok(toDto(saved));
    }

    private CustomerDto toDto(CustomerEntity entity) {
        return new CustomerDto(
                entity.getId(),
                entity.getCompanyName(),
                entity.getTaxId(),
                entity.getEmail(),
                entity.getCity(),
                entity.getUpdatedAt()
        );
    }
}