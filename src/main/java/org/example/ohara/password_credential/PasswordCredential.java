package org.example.ohara.password_credential;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.example.ohara.auth_identity.AuthIdentity;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "password_credential")
public class PasswordCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
//hello 123 3455
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auth_identity_id", nullable = false, unique = true)
    private AuthIdentity authIdentity;

    @Column(nullable = false)
    private String encodedPassword;

    @Column(nullable = false)
    private LocalDateTime passwordChangedAt;

    public PasswordCredential() {
    }

    public PasswordCredential(AuthIdentity authIdentity, String encodedPassword) {
        this.authIdentity = authIdentity;
        this.encodedPassword = encodedPassword;
        this.passwordChangedAt = LocalDateTime.now();
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

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }
}
