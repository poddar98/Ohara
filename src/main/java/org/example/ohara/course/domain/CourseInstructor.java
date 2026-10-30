package org.example.ohara.course.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Course staff. OWNER may publish and manage staff; INSTRUCTOR edits drafts; TA has read access. */
@Entity
@Table(name = "course_instructor")
public class CourseInstructor {

    public enum Role { OWNER, INSTRUCTOR, TA }

    @Embeddable
    public record Key(@Column(name = "course_id") UUID courseId, @Column(name = "user_id") UUID userId) implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private Instant addedAt;

    protected CourseInstructor() {
    }

    public CourseInstructor(UUID courseId, UUID userId, Role role) {
        this.id = new Key(courseId, userId);
        this.role = role;
        this.addedAt = Instant.now();
    }

    public UUID getCourseId() { return id.courseId(); }
    public UUID getUserId() { return id.userId(); }
    public Role getRole() { return role; }
    public Instant getAddedAt() { return addedAt; }

    public void changeRole(Role newRole) {
        this.role = newRole;
    }
}
