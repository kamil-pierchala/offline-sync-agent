package com.pierchala.desktopclient.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pierchala.synccore.model.SyncBatchRequest;
import com.pierchala.synccore.model.SyncBatchResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SyncHttpClient {

    private static final String SERVER_PUSH_URL = "http://localhost:8080/api/sync/push";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SyncHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public SyncBatchResponse pushSync(SyncBatchRequest batchRequest) throws IOException, InterruptedException {
        String jsonPayload = objectMapper.writeValueAsString(batchRequest);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_PUSH_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), SyncBatchResponse.class);
        } else {
            throw new IOException("Server responded with HTTP " + response.statusCode() + ": " + response.body());
        }
    }
}