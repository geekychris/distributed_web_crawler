package com.webcrawler.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.webcrawler.model.CrawlJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence and budget enforcement for {@link CrawlJob}s. Uses Cassandra
 * counter tables to track pages per domain per job; a fresh job is admitted
 * only when its per-job / per-domain / per-domain-count budgets allow it.
 *
 * Not perfectly serialisable: counter reads can lag counter writes by one
 * batch under heavy concurrency, so budgets can be exceeded by O(worker
 * count) pages. Acceptable for a crawler; if strict caps matter, gate on a
 * lightweight-transaction insert instead.
 */
@Service
public class CrawlJobService {
    private static final Logger logger = LoggerFactory.getLogger(CrawlJobService.class);

    private final CqlSession session;
    private final PreparedStatement insertJob;
    private final PreparedStatement selectJob;
    private final PreparedStatement selectAllJobs;
    private final PreparedStatement updateStatus;
    private final PreparedStatement deleteJob;
    private final PreparedStatement incrementDomainCounter;
    private final PreparedStatement selectDomainCounter;
    private final PreparedStatement selectAllDomainCounters;
    private final PreparedStatement insertJobDomain;
    private final PreparedStatement countJobDomains;

    @Autowired
    public CrawlJobService(CqlSession session) {
        this.session = session;
        this.insertJob = session.prepare(
                "INSERT INTO crawl_jobs (job_id, name, status, seed_urls, allowed_domains, "
              + "exclude_patterns, max_depth, max_pages, max_pages_per_domain, max_domains, "
              + "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
        this.selectJob = session.prepare("SELECT * FROM crawl_jobs WHERE job_id = ?");
        this.selectAllJobs = session.prepare("SELECT * FROM crawl_jobs");
        this.updateStatus = session.prepare(
                "UPDATE crawl_jobs SET status = ?, updated_at = ? WHERE job_id = ?");
        this.deleteJob = session.prepare("DELETE FROM crawl_jobs WHERE job_id = ?");
        this.incrementDomainCounter = session.prepare(
                "UPDATE crawl_job_progress SET pages_crawled = pages_crawled + 1 "
              + "WHERE job_id = ? AND domain = ?");
        this.selectDomainCounter = session.prepare(
                "SELECT pages_crawled FROM crawl_job_progress WHERE job_id = ? AND domain = ?");
        this.selectAllDomainCounters = session.prepare(
                "SELECT domain, pages_crawled FROM crawl_job_progress WHERE job_id = ?");
        this.insertJobDomain = session.prepare(
                "INSERT INTO crawl_job_domains (job_id, domain) VALUES (?, ?)");
        this.countJobDomains = session.prepare(
                "SELECT COUNT(*) FROM crawl_job_domains WHERE job_id = ?");
    }

    public CrawlJob create(String name, Set<String> seedUrls, Set<String> allowedDomains,
                           Set<String> excludePatterns, int maxDepth, int maxPages,
                           int maxPagesPerDomain, int maxDomains) {
        Instant now = Instant.now();
        CrawlJob job = new CrawlJob(
                UUID.randomUUID(), name, CrawlJob.Status.PENDING,
                seedUrls == null ? Set.of() : Set.copyOf(seedUrls),
                allowedDomains == null ? Set.of() : Set.copyOf(allowedDomains),
                excludePatterns == null ? Set.of() : Set.copyOf(excludePatterns),
                maxDepth, maxPages, maxPagesPerDomain, maxDomains, now, now);
        session.execute(insertJob.bind(
                job.jobId(), job.name(), job.status().name(),
                job.seedUrls(), job.allowedDomains(), job.excludePatterns(),
                job.maxDepth(), job.maxPages(), job.maxPagesPerDomain(), job.maxDomains(),
                job.createdAt(), job.updatedAt()));
        logger.info("Created crawl job {} '{}' with {} seed(s)", job.jobId(), name, job.seedUrls().size());
        return job;
    }

    public Optional<CrawlJob> get(UUID jobId) {
        Row row = session.execute(selectJob.bind(jobId)).one();
        return Optional.ofNullable(row).map(this::rowToJob);
    }

    public List<CrawlJob> listAll() {
        List<CrawlJob> jobs = new ArrayList<>();
        session.execute(selectAllJobs.bind()).forEach(r -> jobs.add(rowToJob(r)));
        return jobs;
    }

    public void updateStatus(UUID jobId, CrawlJob.Status status) {
        session.execute(updateStatus.bind(status.name(), Instant.now(), jobId));
    }

    public void delete(UUID jobId) {
        session.execute(deleteJob.bind(jobId));
    }

    /**
     * Check whether a page-crawl would be admitted for the given job+domain
     * WITHOUT touching the counters. Callers should then perform the fetch,
     * and only invoke {@link #recordCrawl(UUID, String)} after the page has
     * been successfully stored — that way non-2xx, duplicate-content, and
     * IOException paths do not consume budget.
     *
     * jobId=null (legacy / no-job requests) always passes.
     */
    public boolean canAdmit(UUID jobId, String domain) {
        if (jobId == null) return true;
        Optional<CrawlJob> maybe = get(jobId);
        if (maybe.isEmpty()) return false;
        CrawlJob job = maybe.get();
        if (job.status() != CrawlJob.Status.RUNNING) return false;

        if (job.maxPages() > 0 && totalCrawled(jobId) >= job.maxPages()) return false;

        long domainCrawled = domainCrawled(jobId, domain);
        if (job.maxPagesPerDomain() > 0 && domainCrawled >= job.maxPagesPerDomain()) return false;

        if (job.maxDomains() > 0 && domainCrawled == 0
                && distinctDomains(jobId) >= job.maxDomains()) return false;

        return true;
    }

    /**
     * Record a successful crawl — increments the per-domain page counter and
     * marks the domain as seen for the job. Idempotent-ish: repeated calls
     * increment the counter multiple times, so call exactly once per stored
     * page.
     */
    public void recordCrawl(UUID jobId, String domain) {
        if (jobId == null) return;
        long domainCrawled = domainCrawled(jobId, domain);
        session.execute(incrementDomainCounter.bind(jobId, domain));
        if (domainCrawled == 0) {
            session.execute(insertJobDomain.bind(jobId, domain));
        }
    }

    /**
     * @deprecated Use {@link #canAdmit(UUID, String)} then {@link #recordCrawl(UUID, String)}
     *             so budgets don't decrement on failed / duplicate crawls.
     */
    @Deprecated
    public boolean admit(UUID jobId, String domain) {
        if (!canAdmit(jobId, domain)) return false;
        recordCrawl(jobId, domain);
        return true;
    }

    public long totalCrawled(UUID jobId) {
        long total = 0;
        for (Row r : session.execute(selectAllDomainCounters.bind(jobId))) {
            total += r.getLong("pages_crawled");
        }
        return total;
    }

    public long domainCrawled(UUID jobId, String domain) {
        Row r = session.execute(selectDomainCounter.bind(jobId, domain)).one();
        return r == null ? 0L : r.getLong("pages_crawled");
    }

    public long distinctDomains(UUID jobId) {
        Row r = session.execute(countJobDomains.bind(jobId)).one();
        return r == null ? 0L : r.getLong(0);
    }

    private CrawlJob rowToJob(Row r) {
        return new CrawlJob(
                r.getUuid("job_id"),
                r.getString("name"),
                CrawlJob.Status.valueOf(r.getString("status")),
                new HashSet<>(r.getSet("seed_urls", String.class)),
                new HashSet<>(r.getSet("allowed_domains", String.class)),
                new HashSet<>(r.getSet("exclude_patterns", String.class)),
                r.isNull("max_depth") ? -1 : r.getInt("max_depth"),
                r.getInt("max_pages"),
                r.getInt("max_pages_per_domain"),
                r.getInt("max_domains"),
                r.getInstant("created_at"),
                r.getInstant("updated_at"));
    }
}
