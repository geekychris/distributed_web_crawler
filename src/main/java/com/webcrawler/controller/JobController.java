package com.webcrawler.controller;

import com.webcrawler.model.CrawlJob;
import com.webcrawler.model.CrawlRequest;
import com.webcrawler.queue.UrlQueue;
import com.webcrawler.service.CrawlJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Crawl Jobs", description = "Managed crawl jobs with seeds, scope, and budget")
public class JobController {

    private final CrawlJobService jobs;
    private final UrlQueue urlQueue;

    @Autowired
    public JobController(CrawlJobService jobs, UrlQueue urlQueue) {
        this.jobs = jobs;
        this.urlQueue = urlQueue;
    }

    public record CreateJobRequest(
            String name,
            Set<String> seedUrls,
            Set<String> allowedDomains,
            Set<String> excludePatterns,
            Integer maxDepth,
            Integer maxPages,
            Integer maxPagesPerDomain,
            Integer maxDomains) {}

    @PostMapping
    @Operation(summary = "Create a new crawl job")
    public ResponseEntity<CrawlJob> create(@RequestBody CreateJobRequest req) {
        CrawlJob job = jobs.create(
                req.name(),
                req.seedUrls(),
                req.allowedDomains(),
                req.excludePatterns(),
                req.maxDepth() == null ? -1 : req.maxDepth(),
                req.maxPages() == null ? -1 : req.maxPages(),
                req.maxPagesPerDomain() == null ? -1 : req.maxPagesPerDomain(),
                req.maxDomains() == null ? -1 : req.maxDomains());
        return ResponseEntity.ok(job);
    }

    @GetMapping
    @Operation(summary = "List all jobs")
    public ResponseEntity<List<CrawlJob>> list() {
        return ResponseEntity.ok(jobs.listAll());
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Get a single job")
    public ResponseEntity<CrawlJob> get(@PathVariable UUID jobId) {
        Optional<CrawlJob> job = jobs.get(jobId);
        return job.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{jobId}/start")
    @Operation(summary = "Mark PENDING/PAUSED and enqueue its seeds")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> start(@PathVariable UUID jobId) {
        Optional<CrawlJob> maybe = jobs.get(jobId);
        if (maybe.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }
        CrawlJob job = maybe.get();
        jobs.updateStatus(jobId, CrawlJob.Status.RUNNING);

        List<CompletableFuture<Void>> enqueues = job.seedUrls().stream()
                .map(url -> urlQueue.enqueue(new CrawlRequest(
                        url, 0, null, Instant.now(), 1, 0, null, jobId)))
                .toList();
        return CompletableFuture.allOf(enqueues.toArray(new CompletableFuture[0]))
                .thenApply(v -> ResponseEntity.ok(Map.of(
                        "status", "success",
                        "jobId", jobId.toString(),
                        "seededUrls", job.seedUrls().size())));
    }

    @PostMapping("/{jobId}/pause")
    @Operation(summary = "Pause a running job — new work is not admitted")
    public ResponseEntity<Void> pause(@PathVariable UUID jobId) {
        if (jobs.get(jobId).isEmpty()) return ResponseEntity.notFound().build();
        jobs.updateStatus(jobId, CrawlJob.Status.PAUSED);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/resume")
    @Operation(summary = "Resume a paused job")
    public ResponseEntity<Void> resume(@PathVariable UUID jobId) {
        if (jobs.get(jobId).isEmpty()) return ResponseEntity.notFound().build();
        jobs.updateStatus(jobId, CrawlJob.Status.RUNNING);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/cancel")
    @Operation(summary = "Cancel a job — outstanding enqueued URLs will be rejected")
    public ResponseEntity<Void> cancel(@PathVariable UUID jobId) {
        if (jobs.get(jobId).isEmpty()) return ResponseEntity.notFound().build();
        jobs.updateStatus(jobId, CrawlJob.Status.CANCELLED);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{jobId}/progress")
    @Operation(summary = "Return counts crawled per domain plus totals")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable UUID jobId) {
        Optional<CrawlJob> maybe = jobs.get(jobId);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        long total = jobs.totalCrawled(jobId);
        long distinctDomains = jobs.distinctDomains(jobId);
        return ResponseEntity.ok(Map.of(
                "jobId", jobId.toString(),
                "status", maybe.get().status().name(),
                "totalPages", total,
                "distinctDomains", distinctDomains,
                "maxPages", maybe.get().maxPages(),
                "maxPagesPerDomain", maybe.get().maxPagesPerDomain(),
                "maxDomains", maybe.get().maxDomains()));
    }

    @DeleteMapping("/{jobId}")
    @Operation(summary = "Delete a job (its progress rows are left in place for audit)")
    public ResponseEntity<Void> delete(@PathVariable UUID jobId) {
        jobs.delete(jobId);
        return ResponseEntity.noContent().build();
    }
}
