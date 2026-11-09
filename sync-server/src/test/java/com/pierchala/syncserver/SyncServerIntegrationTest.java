package com.pierchala.syncserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pierchala.synccore.model.OrderDto;
import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;
import com.pierchala.synccore.model.SyncStatus;
import com.pierchala.syncserver.entity.OrderEntity;
import com.pierchala.syncserver.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ServerApp.class)
@AutoConfigureMockMvc
class SyncServerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Test
    void shouldDetectConflictWhenClientPushesOutdatedVersion() throws Exception {
        // There is a record in the Postgres database with version = 2
        UUID orderId = UUID.randomUUID();
        OrderEntity existingEntity = new OrderEntity(
                orderId,
                "ORD-TC-01",
                "Server Customer",
                new BigDecimal("500.00"),
                "NEW",
                Instant.now(),
                2L
        );
        orderRepository.save(existingEntity);

        // Client attempts to trigger an update but is running an old version = 1
        OrderDto clientDto = new OrderDto(
                orderId,
                "ORD-TC-01",
                "Client Conflicted Name",
                new BigDecimal("500.00"),
                "NEW",
                Instant.now(),
                1L
        );

        SyncBatchRequest.OrderSyncPayload payload = new SyncBatchRequest.OrderSyncPayload(
                clientDto,
                SyncStatus.DIRTY_UPDATED
        );
        SyncBatchRequest batchRequest = new SyncBatchRequest("test-client-1", List.of(payload));

        String responseJson = mockMvc.perform(post("/api/sync/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchRequest)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        SyncBatchResponse batchResponse = objectMapper.readValue(responseJson, SyncBatchResponse.class);

        // The server rejects the change with a CONFLICT flag
        assertFalse(batchResponse.processedItems().isEmpty());
        SyncBatchResponse.SyncedItem item = batchResponse.processedItems().get(0);

        assertFalse(item.success());
        assertNotNull(item.errorMessage());
        assertTrue(item.errorMessage().contains("CONFLICT"));

        // We are checking whether the data in the Postgres database has been overwritten
        OrderEntity freshEntity = orderRepository.findById(orderId).orElseThrow();
        assertEquals("Server Customer", freshEntity.getCustomerName());
        assertEquals(2L, freshEntity.getVersion());
    }
}