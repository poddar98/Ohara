package org.example.ohara.enrollment.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.course.api.dto.VersionTree;
import org.example.ohara.course.application.CourseQueries;
import org.example.ohara.content.application.AssetQueries;
import org.example.ohara.content.domain.ContentAsset;
import org.example.ohara.enrollment.api.dto.LearnerCurriculum;
import org.example.ohara.enrollment.api.dto.LessonView;
import org.example.ohara.enrollment.api.dto.MyCourseResponse;
import org.example.ohara.enrollment.domain.CourseOffering;
import org.example.ohara.enrollment.domain.Enrollment;
import org.example.ohara.enrollment.infrastructure.persistence.CourseOfferingRepository;
import org.example.ohara.enrollment.infrastructure.persistence.EnrollmentRepository;
import org.example.ohara.enrollment.infrastructure.persistence.LessonProgressRepository;
import org.example.ohara.enrollment.domain.LessonProgress;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

/** What a learner sees: their courses, the outline of the version they study, and lessons they may open. */
@Service
public class LearnerService {

    private final EnrollmentRepository enrollments;
    private final CourseOfferingRepository offerings;
    private final CourseQueries courses;
    private final ContentAccessService access;
    private final LessonProgressRepository progress;
    private final AssetQueries assets;

    public LearnerService(EnrollmentRepository enrollments, CourseOfferingRepository offerings, CourseQueries courses,
                          ContentAccessService access, LessonProgressRepository progress, AssetQueries assets) {
        this.enrollments = enrollments;
        this.offerings = offerings;
        this.courses = courses;
        this.access = access;
        this.progress = progress;
        this.assets = assets;
    }

    @Transactional(readOnly = true)
    public List<MyCourseResponse> myCourses(CurrentActor actor) {
        return enrollments.findByUserIdOrderByEnrolledAtDesc(actor.userId()).stream().map(e -> {
            CourseOffering offering = offerings.findById(e.getCourseOfferingId()).orElseThrow();
            String title = courses.course(e.getCourseId()).map(CourseQueries.CourseFacts::title).orElse(null);
            return new MyCourseResponse(e.getId(), e.getCourseId(), title, offering.getId(), offering.getChannel(),
                e.getOrganizationId(), e.getCourseVersionId(), e.getStatus(), access.enrollmentAccess(e).allowed(),
                access.accessUntil(e), e.getEnrolledAt());
        }).toList();
    }

    /** Outline of the enrolled version. Without current access everything but preview lessons is locked. */
    @Transactional(readOnly = true)
    public LearnerCurriculum curriculum(CurrentActor actor, UUID enrollmentId) {
        Enrollment enrollment = enrollments.findByIdAndUserId(enrollmentId, actor.userId())
            .orElseThrow(() -> new ApiException.NotFound("enrollment_not_found", "Enrollment not found"));
        boolean hasAccess = access.enrollmentAccess(enrollment).allowed();
        VersionTree tree = courses.tree(enrollment.getCourseVersionId()).orElseThrow();
        String title = courses.course(enrollment.getCourseId()).map(CourseQueries.CourseFacts::title).orElse(tree.title());
        Map<UUID, LessonProgress> byKey = progress.findByEnrollmentId(enrollment.getId()).stream()
            .collect(Collectors.toMap(LessonProgress::getLessonKey, Function.identity()));
        List<LearnerCurriculum.Module> modules = tree.modules().stream()
            .map(m -> new LearnerCurriculum.Module(m.id(), m.title(), m.lessons().stream()
                .map(l -> new LearnerCurriculum.LessonItem(l.id(), l.lessonKey(), l.title(), l.type().name(), l.preview(),
                    l.durationSeconds(), !hasAccess && !l.preview(),
                    byKey.containsKey(l.lessonKey()) ? byKey.get(l.lessonKey()).getStatus().name() : "NOT_STARTED"))
                .toList()))
            .toList();
        return new LearnerCurriculum(enrollment.getId(), enrollment.getCourseId(), title, enrollment.getCourseVersionId(),
            hasAccess, access.accessUntil(enrollment), enrollment.getProgressPercent(), modules);
    }

    /**
     * A lesson's content, if {@link ContentAccessService} allows it. Lessons the caller has no relationship with are
     * reported as not found; known-but-locked ones as forbidden with the reason.
     */
    @Transactional(readOnly = true)
    public LessonView lesson(CurrentActor actor, UUID lessonId) {
        ContentAccessService.Decision decision = access.lessonAccess(actor, lessonId);
        if (!decision.allowed()) {
            switch (decision.reason()) {
                case NOT_FOUND, NOT_ENROLLED -> throw new ApiException.NotFound("lesson_not_found", "Lesson not found");
                case ORGANIZATION_INACTIVE -> throw new ApiException.Forbidden("organization_inactive",
                    "Your organization's access is currently suspended");
                default -> throw new ApiException.Forbidden("access_expired", "Your access to this course has ended");
            }
        }
        CourseQueries.LessonFacts facts = courses.lesson(lessonId).orElseThrow();
        VersionTree.LessonNode node = courses.tree(facts.versionId()).orElseThrow().modules().stream()
            .flatMap(m -> m.lessons().stream())
            .filter(l -> l.id().equals(lessonId))
            .findFirst().orElseThrow();
        Long resume = decision.enrollmentId() == null ? null
            : progress.findByEnrollmentId(decision.enrollmentId()).stream()
                .filter(p -> p.getLessonKey().equals(node.lessonKey()))
                .map(LessonProgress::getVideoPositionMs).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        var assetIds = node.contents().stream().map(VersionTree.ContentNode::contentAssetId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, AssetQueries.AssetFacts> assetFacts = assetIds.isEmpty() ? Map.of() : assets.assets(assetIds);
        List<LessonView.ContentItem> items = node.contents().stream().map(c -> {
            if (c.contentAssetId() == null) {
                return new LessonView.ContentItem(c.id(), c.kind().name(), c.title(), c.body(), c.externalUrl(), null, true,
                    null, c.position(), c.required());
            }
            AssetQueries.AssetFacts asset = assetFacts.get(c.contentAssetId());
            boolean available = asset != null && asset.status() == ContentAsset.Status.READY;
            java.net.URI download = available && asset.type() == ContentAsset.Type.DOCUMENT
                ? assets.documentLink(asset.id()).orElse(null) : null;
            return new LessonView.ContentItem(c.id(), c.kind().name(), c.title() != null ? c.title() : asset == null ? null : asset.title(),
                null, null, c.contentAssetId(), available, download, c.position(), c.required());
        }).toList();
        Instant accessUntil = decision.enrollmentId() == null ? null
            : enrollments.findById(decision.enrollmentId()).map(access::accessUntil).orElse(null);
        return new LessonView(node.id(), node.lessonKey(), node.title(), node.type().name(), decision.enrollmentId(), resume,
            accessUntil, items);
    }
}
