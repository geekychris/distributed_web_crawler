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
@RequestMapping("/api/data")
@Tag(name = "Data Access", description = "APIs for querying crawled data")
public class DataController {

    private final CrawlerUIService crawlerUIService;

    @Autowired
    public DataController(CrawlerUIService crawlerUIService) {
        this.crawlerUIService = crawlerUIService;
    }

    @GetMapping("/pages")
    @Operation(
        summary = "Get all pages",
        description = "Retrieve a paginated list of all crawled pages",
        responses = {
            @ApiResponse(responseCode = "200", description = "Pages retrieved successfully")
        }
    )
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getAllPages(
        @RequestParam(defaultValue = "50") @Parameter(description = "Number of pages to return") int limit,
        @RequestParam(defaultValue = "0") @Parameter(description = "Number of pages to skip") int offset
    ) {
        return crawlerUIService.getAllPages(limit, offset)
            .thenApply(pages -> ResponseEntity.ok(Map.of(
                "status", "success",
                "pages", pages,
                "count", pages.size(),
                "limit", limit,
                "offset", offset
            )))
            .exceptionally(throwable -> ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to retrieve pages: " + throwable.getMessage()
            )));
    }

    @GetMapping("/pages/search")
    @Operation(
        summary = "Search pages",
        description = "Search for pages containing the specified term",
        responses = {
            @ApiResponse(responseCode = "200", description = "Search completed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid search parameters")
        }
    )
    public CompletableFuture<ResponseEntity<Map<String, Object>>> searchPages(
        @RequestParam @Parameter(description = "Search term", required = true) String query,
        @RequestParam(defaultValue = "50") @Parameter(description = "Maximum number of results") int limit
    ) {
        if (query == null || query.trim().isEmpty()) {
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Search query cannot be empty"
            )));
        }

        return crawlerUIService.searchPages(query.trim(), limit)
            .thenApply(pages -> ResponseEntity.ok(Map.of(
                "status", "success",
                "query", query.trim(),
                "pages", pages,
                "count", pages.size(),
                "limit", limit
            )))
            .exceptionally(throwable -> ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Search failed: " + throwable.getMessage()
            )));
    }

    @GetMapping("/pages/count")
    @Operation(
        summary = "Get total page count",
        description = "Get the total number of pages crawled and stored",
        responses = {
            @ApiResponse(responseCode = "200", description = "Count retrieved successfully")
        }
    )
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getPageCount() {
        return crawlerUIService.getPageCount()
            .thenApply(count -> {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("totalPages", count);
                return ResponseEntity.ok(response);
            })
            .exceptionally(throwable -> {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("message", "Failed to get page count: " + throwable.getMessage());
                return ResponseEntity.internalServerError().body(errorResponse);
            });
    }

    @GetMapping("/stats")
    @Operation(
        summary = "Get data statistics",
        description = "Get comprehensive statistics about the crawled data",
        responses = {
            @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
        }
    )
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getDataStats() {
        return crawlerUIService.getPageCount()
            .thenApply(totalPages -> ResponseEntity.ok(Map.of(
                "status", "success",
                "statistics", Map.of(
                    "totalPages", totalPages,
                    "lastUpdated", System.currentTimeMillis()
                )
            )))
            .exceptionally(throwable -> ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to get statistics: " + throwable.getMessage()
            )));
    }
}