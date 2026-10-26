package com.pierchala.syncserver.controller;

import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;
import com.pierchala.synccore.model.SyncPullResponse;
import com.pierchala.syncserver.service.SyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

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

    @GetMapping("/pull")
    public ResponseEntity<SyncPullResponse> pullSync(
            @RequestParam(name = "since", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant since) {
        SyncPullResponse response = syncService.fetchUpdatesSince(since);
        return ResponseEntity.ok(response);
    }
}