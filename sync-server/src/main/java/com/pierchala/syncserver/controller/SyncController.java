package com.pierchala.syncserver.controller;

import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;
import com.pierchala.syncserver.service.SyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/push")
    public ResponseEntity<SyncBatchResponse> pushSync(@RequestBody SyncBatchRequest request) {
        SyncBatchResponse response = syncService.processBatch(request);
        return ResponseEntity.ok(response);
    }
}