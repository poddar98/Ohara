package org.example.ohara.enrollment.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.api.dto.VersionTree;
import org.example.ohara.course.application.CourseQueries;
import org.example.ohara.course.domain.Lesson;
import org.example.ohara.enrollment.api.dto.ContinueItem;
import org.example.ohara.enrollment.api.dto.HeartbeatRequest;
import org.example.ohara.enrollment.api.dto.MigrationResponse;
import org.example.ohara.enrollment.api.dto.ProgressSummary;
import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.domain.LessonProgress;
import org.example.ohara.enrollment.infrastructure.persistence.CourseOfferingRepository;
import org.example.ohara.enrollment.infrastructure.persistence.EnrollmentRepository;
import org.example.ohara.enrollment.infrastructure.persistence.LessonProgressRepository;
import org.example.ohara.organization.application.OrgAuthorizationService;
import org.example.ohara.organization.application.OrgPermission;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Learner progress. Writes require access to the lesson through this very enrollment (or it being a free preview);
 * the enrollment's percentage is recomputed whenever a lesson completes, counting only lessons of its current version.
 */
@Service
public class ProgressService {

    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository progress;
    private final CourseOfferingRepository offerings;
    private final CourseQueries courses;
    private final ContentAccessService access;
    private final OrgAuthorizationService orgAuthorization;

    public ProgressService(EnrollmentRepository enrollments, LessonProgressRepository progress, CourseOfferingRepository offerings,
                           CourseQueries courses, ContentAccessService access, OrgAuthorizationService orgAuthorization) {
        this.enrollments = enrollments;
        this.progress = progress;
        this.offerings = offerings;
        this.courses = courses;
        this.access = access;
        this.orgAuthorization = orgAuthorization;
    }

    @Transactional
    public void heartbeat(CurrentActor actor, UUID enrollmentId, UUID lessonKey, HeartbeatRequest r) {
        Enrollment enrollment = ownEnrollment(actor, enrollmentId);
        VersionTree tree = tree(enrollment);
        VersionTree.LessonNode lesson = accessibleLesson(actor, enrollment, tree, lessonKey);
        Instant now = Instant.now();

        Optional<LessonProgress> existing = progress.lockOne(enrollmentId, lessonKey);
        LessonProgress row = existing.orElseGet(() -> new LessonProgress(enrollment, lessonKey, lesson.id()));
        Long knownDurationMs = lesson.durationSeconds() == null ? null : lesson.durationSeconds() * 1000L;
        boolean completed = row.heartbeat(lesson.id(), r.positionMs(), r.durationMs(), r.watchedDeltaMs(), r.clientTs(),
            knownDurationMs, lesson.type() == Lesson.Type.VIDEO, now, existing.isEmpty());
        save(row);
        if (completed) {
            recompute(enrollment, tree, now);
        } else {
            enrollment.touchActivity(now);
        }
    }

    /** Marks a lesson done (reading lessons, or "mark as complete"). Idempotent. */
    @Transactional
    public ProgressSummary complete(CurrentActor actor, UUID enrollmentId, UUID lessonKey) {
        Enrollment enrollment = ownEnrollment(actor, enrollmentId);
        VersionTree tree = tree(enrollment);
        VersionTree.LessonNode lesson = accessibleLesson(actor, enrollment, tree, lessonKey);
        Instant now = Instant.now();
        LessonProgress row = progress.lockOne(enrollmentId, lessonKey)
            .orElseGet(() -> new LessonProgress(enrollment, lessonKey, lesson.id()));
        if (row.complete(now) || row.getId() == null) {
            save(row);
            recompute(enrollment, tree, now);
        }
        return summary(enrollment, tree);
    }

    @Transactional(readOnly = true)
    public ProgressSummary summary(CurrentActor actor, UUID enrollmentId) {
        Enrollment enrollment = ownEnrollment(actor, enrollmentId);
        return summary(enrollment, tree(enrollment));
    }

    /** Most recently watched unfinished lessons that the learner can still open. */
    @Transactional(readOnly = true)
    public List<ContinueItem> continueWatching(CurrentActor actor, int limit) {
        List<LessonProgress> recent = progress.findRecentInProgress(actor.userId(), PageRequest.ofSize(Math.clamp(limit, 1, 50) * 2));
        Map<UUID, Enrollment> byId = enrollments.findAllById(recent.stream().map(LessonProgress::getEnrollmentId).distinct().toList())
            .stream().collect(Collectors.toMap(Enrollment::getId, Function.identity()));
        return recent.stream()
            .map(p -> {
                Enrollment e = byId.get(p.getEnrollmentId());
                if (e == null || !access.enrollmentAccess(e).allowed()) {
                    return null;
                }
                VersionTree tree = tree(e);
                return lessonByKey(tree, p.getLessonKey())
                    .map(l -> new ContinueItem(e.getId(), e.getCourseId(),
                        courses.course(e.getCourseId()).map(CourseQueries.CourseFacts::title).orElse(tree.title()),
                        l.id(), l.lessonKey(), l.title(), p.getVideoPositionMs(), p.getVideoDurationMs(), p.getUpdatedAt()))
                    .orElse(null);
            })
            .filter(Objects::nonNull)
            .limit(Math.clamp(limit, 1, 50))
            .toList();
    }

    /**
     * Moves an enrollment to the course's current published version. Learners may do it themselves unless the offering
     * pins a version; the organization's admins (and platform admins) may always. Progress carries over by lesson key.
     */
    @Transactional
    public MigrationResponse migrateToLatest(CurrentActor actor, UUID enrollmentId) {
        Enrollment enrollment = enrollments.findById(enrollmentId)
            .orElseThrow(ProgressService::enrollmentNotFound);
        CourseOffering offering = offerings.findById(enrollment.getCourseOfferingId()).orElseThrow();
        boolean owner = enrollment.getUserId().equals(actor.userId());
        boolean orgAdmin = enrollment.getOrganizationId() != null
            && orgAuthorization.allows(actor, enrollment.getOrganizationId(), OrgPermission.MANAGE_COURSES);
        if (!owner && !orgAdmin && !actor.isPlatformAdmin()) {
            throw enrollmentNotFound();
        }
        if (owner && !orgAdmin && !actor.isPlatformAdmin() && offering.getVersionPolicy() == CourseOffering.VersionPolicy.PINNED) {
            throw new ApiException.Forbidden("version_pinned", "Your course version is fixed by your organization");
        }
        UUID latest = courses.course(enrollment.getCourseId()).map(CourseQueries.CourseFacts::currentPublishedVersionId)
            .orElseThrow();
        if (latest.equals(enrollment.getCourseVersionId())) {
            throw new ApiException.Conflict("already_latest", "This enrollment is already on the latest version");
        }
        UUID from = enrollment.getCourseVersionId();
        enrollment.migrateTo(latest);
        recompute(enrollment, tree(enrollment), Instant.now());
        return new MigrationResponse(enrollment.getId(), from, latest, enrollment.getProgressPercent());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void recompute(Enrollment enrollment, VersionTree tree, Instant now) {
        Set<UUID> keys = lessonKeys(tree);
        long done = progress.findByEnrollmentId(enrollment.getId()).stream()
            .filter(p -> p.getStatus() == LessonProgress.Status.COMPLETED && keys.contains(p.getLessonKey()))
            .count();
        BigDecimal percent = keys.isEmpty() ? BigDecimal.ZERO
            : BigDecimal.valueOf(done * 100).divide(BigDecimal.valueOf(keys.size()), 2, RoundingMode.HALF_UP);
        enrollment.progressed(percent, now);
    }

    private ProgressSummary summary(Enrollment enrollment, VersionTree tree) {
        Set<UUID> keys = lessonKeys(tree);
        List<ProgressSummary.Item> items = progress.findByEnrollmentId(enrollment.getId()).stream()
            .filter(p -> keys.contains(p.getLessonKey()))
            .map(p -> new ProgressSummary.Item(p.getLessonKey(), p.getLessonId(), p.getStatus(), p.getVideoPositionMs(),
                p.getVideoDurationMs(), p.getWatchedMs(), p.getCompletedAt(), p.getUpdatedAt()))
            .toList();
        int completed = (int) items.stream().filter(i -> i.status() == LessonProgress.Status.COMPLETED).count();
        return new ProgressSummary(enrollment.getId(), enrollment.getCourseVersionId(), enrollment.getStatus(),
            enrollment.getProgressPercent(), completed, keys.size(), enrollment.getCompletedAt(), items);
    }

    /** The lesson with this key in the enrollment's version, if the learner may work on it through this enrollment. */
    private VersionTree.LessonNode accessibleLesson(CurrentActor actor, Enrollment enrollment, VersionTree tree, UUID lessonKey) {
        VersionTree.LessonNode lesson = lessonByKey(tree, lessonKey)
            .orElseThrow(() -> new ApiException.NotFound("lesson_not_found", "Lesson not found in this course version"));
        ContentAccessService.Decision decision = access.lessonAccess(actor, lesson.id());
        boolean viaThisEnrollment = decision.allowed() && enrollment.getId().equals(decision.enrollmentId());
        boolean preview = decision.allowed() && decision.reason() == ContentAccessService.Reason.PREVIEW;
        if (!viaThisEnrollment && !preview) {
            throw new ApiException.Forbidden("access_expired", "Your access to this course has ended");
        }
        return lesson;
    }

    private void save(LessonProgress row) {
        try {
            progress.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            // Two first heartbeats raced; the other one created the row. The client sends another within seconds.
            throw new ApiException.Conflict("progress_conflict", "Progress was updated concurrently; retry");
        }
    }

    private Enrollment ownEnrollment(CurrentActor actor, UUID enrollmentId) {
        return enrollments.findByIdAndUserId(enrollmentId, actor.userId()).orElseThrow(ProgressService::enrollmentNotFound);
    }

    private VersionTree tree(Enrollment enrollment) {
        return courses.tree(enrollment.getCourseVersionId()).orElseThrow();
    }

    private static Optional<VersionTree.LessonNode> lessonByKey(VersionTree tree, UUID lessonKey) {
        return tree.modules().stream().flatMap(m -> m.lessons().stream()).filter(l -> l.lessonKey().equals(lessonKey)).findFirst();
    }

    private static Set<UUID> lessonKeys(VersionTree tree) {
        return tree.modules().stream().flatMap(m -> m.lessons().stream()).map(VersionTree.LessonNode::lessonKey)
            .collect(Collectors.toSet());
    }

    private static ApiException enrollmentNotFound() {
        return new ApiException.NotFound("enrollment_not_found", "Enrollment not found");
    }
}
