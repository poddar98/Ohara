package org.example.ohara.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One consent decision. Rows are never updated; the newest row per type is the current state. */
@Entity
@Table(name = "user_consent")
public class UserConsent {

    public enum Type { TERMS, PRIVACY, MARKETING_EMAIL }

    public enum Source { SIGNUP, INVITATION, SETTINGS }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false)
    private Type type;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private boolean granted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(nullable = false)
    private Instant recordedAt;

    protected UserConsent() {
    }

    public UserConsent(UUID userId, Type type, String version, boolean granted, Source source) {
        this.userId = userId;
        this.type = type;
        this.version = version;
        this.granted = granted;
        this.source = source;
        this.recordedAt = Instant.now();
    }

    public Type getType() { return type; }
    public String getVersion() { return version; }
    public boolean isGranted() { return granted; }
    public Source getSource() { return source; }
    public Instant getRecordedAt() { return recordedAt; }
}
