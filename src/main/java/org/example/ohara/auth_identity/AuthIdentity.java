package org.example.ohara.auth_identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Setter;
import org.example.ohara.password_credential.PasswordCredential;
import org.example.ohara.user_profile.UserProfile;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "auth_identity",
    uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "identifier"})
)
public class AuthIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_profile_id", nullable = false, unique = true)
    private UserProfile userProfile;

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

    public AuthIdentity(UserProfile userProfile, String provider, String identifier) {
        this.userProfile = userProfile;
        this.provider = provider;
        this.identifier = identifier;
    }

    public Long getId() {
        return id;
    }

    public UserProfile getUserProfile() {
        return userProfile;
    }

    public void setUserProfile(UserProfile userProfile) {
        this.userProfile = userProfile;
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
