package org.example.ohara.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Setter;
import org.example.ohara.user.domain.User;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "auth_identity",
    uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "identifier"})
)
public class AuthIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String identifier;

    @Column
    private LocalDateTime verifiedAt;

    @Setter
    @OneToOne(mappedBy = "authIdentity", fetch = FetchType.LAZY)
    private PasswordCredential passwordCredential;

    public AuthIdentity() {
    }

    public AuthIdentity(User user, String provider, String identifier) {
        this.user = user;
        this.provider = provider;
        this.identifier = identifier;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public PasswordCredential getPasswordCredential() {
        return passwordCredential;
    }

}
