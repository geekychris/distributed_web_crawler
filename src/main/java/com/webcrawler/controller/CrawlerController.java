package com.webcrawler.controller;

import com.webcrawler.service.CrawlerUIService;
import com.webcrawler.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/crawler")
@Tag(name = "Crawler Management", description = "APIs for managing the distributed web crawler")
public class CrawlerController {

    private final CrawlerUIService crawlerUIService;

    @Autowired
    public CrawlerController(CrawlerUIService crawlerUIService) {
        this.crawlerUIService = crawlerUIService;
    }

    @PostMapping("/start")
    @Operation(
        summary = "Start the web crawler",
        description = "Starts the distributed web crawler service",
        responses = {
            @ApiResponse(responseCode = "200", description = "Crawler started successfully"),
            @ApiResponse(responseCode = "400", description = "Crawler is already running")
        }
    )
    public ResponseEntity<Map<String, Object>> startCrawler() {
        try {
            crawlerUIService.startCrawler();
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Crawler started successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Failed to start crawler: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/stop")
    @Operation(
        summary = "Stop the web crawler",
        description = "Stops the distributed web crawler service",
        responses = {
            @ApiResponse(responseCode = "200", description = "Crawler stopped successfully")
        }
    )
    public ResponseEntity<Map<String, Object>> stopCrawler() {
        crawlerUIService.stopCrawler();
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Crawler stopped successfully"
        ));
    }

    @GetMapping("/status")
    @Operation(
        summary = "Get crawler status",
        description = "Returns the current status and configuration of the web crawler",
        responses = {
            @ApiResponse(responseCode = "200", description = "Status retrieved successfully")
        }
    )
    public ResponseEntity<CrawlerUIService.CrawlerStats> getStatus() {
        return ResponseEntity.ok(crawlerUIService.getCrawlerStats());
    }

    @PostMapping("/urls")
    @Operation(
        summary = "Add seed URLs",
        description = "Add one or more URLs to the crawling queue",
        responses = {
            @ApiResponse(responseCode = "200", description = "URLs added successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid URL format")
        }
    )
    public CompletableFuture<ResponseEntity<Map<String, Object>>> addUrls(
        @RequestBody @Parameter(description = "List of URLs to add", required = true) 
        AddUrlsRequest request
    ) {
        return crawlerUIService.addSeedUrls(request.urls())
            .thenApply(result -> ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Added " + request.urls().size() + " URLs to crawling queue",
                "urls", request.urls()
            )))
            .exceptionally(throwable -> ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Failed to add URLs: " + throwable.getMessage()
            )));
    }

    @PostMapping("/url")
    @Operation(
        summary = "Add single seed URL",
        description = "Add a single URL to the crawling queue",
        responses = {
            @ApiResponse(responseCode = "200", description = "URL added successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid URL format")
        }
    )
    public CompletableFuture<ResponseEntity<Map<String, Object>>> addUrl(
        @RequestBody @Parameter(description = "URL to add", required = true) 
        AddUrlRequest request
    ) {
        return crawlerUIService.addSeedUrl(request.url())
            .thenApply(result -> {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("message", "URL added to crawling queue");
                response.put("url", request.url());
                return ResponseEntity.ok(response);
            })
            .exceptionally(throwable -> {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("message", "Failed to add URL: " + throwable.getMessage());
                return ResponseEntity.badRequest().body(errorResponse);
            });
    }

    // Request DTOs
    public record AddUrlRequest(String url) {}
    public record AddUrlsRequest(List<String> urls) {}
}