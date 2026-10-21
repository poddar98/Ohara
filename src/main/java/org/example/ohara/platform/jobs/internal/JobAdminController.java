package org.example.ohara.platform.jobs.internal;

import org.example.ohara.common.web.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operational view of background jobs: find stuck or dead work and retry or cancel it. */
@RestController
@RequestMapping("/api/admin/jobs")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class JobAdminController {

    record JobView(UUID id, String jobType, String status, int attempt, int maxAttempts, String lastError,
                   Instant runAfter, Instant createdAt, Instant finishedAt) {

        static JobView of(JobRecord job) {
            return new JobView(job.id(), job.jobType(), job.status(), job.attempt(), job.maxAttempts(), job.lastError(),
                job.runAfter(), job.createdAt(), job.finishedAt());
        }
    }

    private final JobStore store;

    JobAdminController(JobStore store) {
        this.store = store;
    }

    @GetMapping
    List<JobView> search(@RequestParam(required = false) String type,
                         @RequestParam(required = false) String status,
                         @RequestParam(defaultValue = "50") int limit) {
        return store.search(type, status, Math.clamp(limit, 1, 200)).stream().map(JobView::of).toList();
    }

    @GetMapping("/{id}")
    JobView get(@PathVariable UUID id) {
        return store.find(id).map(JobView::of).orElseThrow(JobAdminController::notFound);
    }

    @PostMapping("/{id}/retry")
    ResponseEntity<JobView> retry(@PathVariable UUID id) {
        JobRecord job = store.find(id).orElseThrow(JobAdminController::notFound);
        if (!store.retry(id)) {
            throw new ApiException.Conflict("job_not_retryable", "Only FAILED, DEAD or CANCELLED jobs can be retried (job is " + job.status() + ")");
        }
        return ResponseEntity.accepted().body(get(id));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<JobView> cancel(@PathVariable UUID id) {
        JobRecord job = store.find(id).orElseThrow(JobAdminController::notFound);
        if (!store.cancel(id)) {
            throw new ApiException.Conflict("job_not_cancellable", "Only PENDING jobs can be cancelled (job is " + job.status() + ")");
        }
        return ResponseEntity.ok(get(id));
    }

    private static ApiException notFound() {
        return new ApiException.NotFound("job_not_found", "Job not found");
    }
}
