package com.learning.parkinglot.integration;

import com.learning.parkinglot.dto.VehicleRequest;
import com.learning.parkinglot.model.Ticket;
import com.learning.parkinglot.model.VehicleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Parking Lot system.
 * Tests full API endpoints and concurrent operations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParkingLotIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Full workflow: Entry -> Check availability -> Exit")
    void testFullWorkflow() throws InterruptedException {
        // Step 1: Park a vehicle
        VehicleRequest request = new VehicleRequest("TEST123", VehicleType.CAR);
        ResponseEntity<Ticket> entryResponse = restTemplate.postForEntity(
                "/api/parking/entry",
                request,
                Ticket.class
        );

        assertThat(entryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entryResponse.getBody()).isNotNull();
        String ticketId = entryResponse.getBody().ticketId();

        // Step 2: Check availability
        ResponseEntity<String> availabilityResponse = restTemplate.getForEntity(
                "/api/parking/availability",
                String.class
        );

        assertThat(availabilityResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Wait a bit to ensure parking duration
        Thread.sleep(100);

        // Step 3: Exit vehicle
        ResponseEntity<String> exitResponse = restTemplate.postForEntity(
                "/api/parking/exit/" + ticketId,
                null,
                String.class
        );

        assertThat(exitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Concurrent entry operations")
    void testConcurrentEntry() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    VehicleRequest request = new VehicleRequest(
                            "CONCURRENT-" + index,
                            VehicleType.CAR
                    );

                    ResponseEntity<Ticket> response = restTemplate.postForEntity(
                            "/api/parking/entry",
                            request,
                            Ticket.class
                    );

                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // All vehicles should be parked successfully (we have enough spots)
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("Concurrent entry and exit operations")
    void testConcurrentEntryAndExit() throws InterruptedException {
        int vehicleCount = 10;

        // First, park some vehicles
        List<String> ticketIds = new ArrayList<>();
        for (int i = 0; i < vehicleCount; i++) {
            VehicleRequest request = new VehicleRequest("MIXED-" + i, VehicleType.CAR);
            ResponseEntity<Ticket> response = restTemplate.postForEntity(
                    "/api/parking/entry",
                    request,
                    Ticket.class
            );
            if (response.getBody() != null) {
                ticketIds.add(response.getBody().ticketId());
            }
        }

        // Now, concurrently: exit half and park new vehicles
        int operationCount = vehicleCount;
        ExecutorService executorService = Executors.newFixedThreadPool(operationCount);
        CountDownLatch latch = new CountDownLatch(operationCount);

        // Exit first 5 vehicles
        for (int i = 0; i < vehicleCount / 2; i++) {
            final String ticketId = ticketIds.get(i);
            executorService.submit(() -> {
                try {
                    restTemplate.postForEntity(
                            "/api/parking/exit/" + ticketId,
                            null,
                            String.class
                    );
                } finally {
                    latch.countDown();
                }
            });
        }

        // Park 5 new vehicles
        for (int i = 0; i < vehicleCount / 2; i++) {
            final int index = i;
            executorService.submit(() -> {
                try {
                    VehicleRequest request = new VehicleRequest(
                            "NEW-" + index,
                            VehicleType.MOTORCYCLE
                    );
                    restTemplate.postForEntity(
                            "/api/parking/entry",
                            request,
                            Ticket.class
                    );
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Verify system is still operational
        ResponseEntity<String> availabilityResponse = restTemplate.getForEntity(
                "/api/parking/availability",
                String.class
        );
        assertThat(availabilityResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Invalid ticket returns 404")
    void testInvalidTicket() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/parking/exit/INVALID-TICKET",
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Invalid vehicle request returns 400")
    void testInvalidVehicleRequest() {
        VehicleRequest request = new VehicleRequest("", null); // Invalid

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/parking/entry",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Health check returns OK")
    void testHealthCheck() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/parking/health",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("operational");
    }
}
