package org.example.ohara.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_credential")
public class PasswordCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auth_identity_id", nullable = false, unique = true)
    private AuthIdentity authIdentity;

    @Column(nullable = false)
    private String encodedPassword;

    @Column(nullable = false)
    private Instant passwordChangedAt;

    @Column(nullable = false)
    private int failedAttempts;

    @Column
    private Instant lockedUntil;

    public PasswordCredential() {
    }

    public PasswordCredential(AuthIdentity authIdentity, String encodedPassword) {
        this.authIdentity = authIdentity;
        this.encodedPassword = encodedPassword;
        this.passwordChangedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public AuthIdentity getAuthIdentity() {
        return authIdentity;
    }

    public void setAuthIdentity(AuthIdentity authIdentity) {
        this.authIdentity = authIdentity;
    }

    public String getEncodedPassword() {
        return encodedPassword;
    }

    public void setEncodedPassword(String encodedPassword) {
        this.encodedPassword = encodedPassword;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Every 5th consecutive failure locks the credential: 15, 30, then 60 minutes. */
    /** Counts a failed attempt; every fifth locks the account for a while. Returns true when this attempt locked it. */
    public boolean recordFailedLogin(Instant now) {
        failedAttempts++;
        if (failedAttempts % 5 == 0) {
            long minutes = Math.min(60, 15L << Math.min(2, failedAttempts / 5 - 1));
            lockedUntil = now.plus(Duration.ofMinutes(minutes));
            return true;
        }
        return false;
    }

    public void recordSuccessfulLogin() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public void changePassword(String encodedPassword, Instant now) {
        this.encodedPassword = encodedPassword;
        this.passwordChangedAt = now;
        recordSuccessfulLogin();
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }
}
