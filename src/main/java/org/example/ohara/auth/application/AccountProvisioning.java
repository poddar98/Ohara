package org.example.ohara.auth.application;

import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.domain.PasswordCredential;
import org.example.ohara.auth.infrastructure.persistence.AuthIdentityRepository;
import org.example.ohara.auth.infrastructure.persistence.PasswordCredentialRepository;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserProfile;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.domain.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;

/**
 * Creates accounts and sessions for flows outside self-registration (invitations, bulk import).
 * One account per email, always: callers look up first and only create when none exists.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AccountProvisioning {

    private final UserService userService;
    private final AuthIdentityRepository authIdentityRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final AccountRecoveryService accountRecoveryService;

    public AccountProvisioning(UserService userService,
                               AuthIdentityRepository authIdentityRepository,
                               PasswordCredentialRepository passwordCredentialRepository,
                               PasswordEncoder passwordEncoder,
                               TokenIssuer tokenIssuer,
                               AccountRecoveryService accountRecoveryService) {
        this.userService = userService;
        this.authIdentityRepository = authIdentityRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.accountRecoveryService = accountRecoveryService;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<User> findAccount(String email) {
        return userService.findByEmail(normalizeEmail(email));
    }

    /** Self-registration: unverified until the verification link is used. */
    User createPendingAccount(String email, String firstName, String lastName, String rawPassword) {
        return create(email, firstName, lastName, rawPassword, false);
    }

    /** The caller has proven the email (e.g. used an emailed invitation link), so the account starts ACTIVE. */
    public User createVerifiedAccount(String email, String firstName, String lastName, String rawPassword) {
        return create(email, firstName, lastName, rawPassword, true);
    }

    /**
     * Admin-provisioned account with no password. It stays unverified until the person uses the emailed
     * "set your password" link, which also proves the email.
     */
    public User createAccountWithoutPassword(String email, String firstName, String lastName) {
        User user = create(email, firstName, lastName, null, false);
        accountRecoveryService.sendSetPassword(user, Instant.now());
        return user;
    }

    /** Proof of email obtained elsewhere (e.g. an invitation link reached this inbox). */
    public void markEmailVerified(User user) {
        Instant now = Instant.now();
        user.markEmailVerified(now);
        authIdentityRepository.findByUserAndProvider(user, AuthIdentity.LOCAL_PROVIDER).ifPresent(i -> i.setVerifiedAt(now));
    }

    public IssuedTokens startSession(User user) {
        return tokenIssuer.issue(user);
    }

    private User create(String rawEmail, String firstName, String lastName, String rawPassword, boolean verified) {
        String email = normalizeEmail(rawEmail);
        Instant now = Instant.now();
        User user = new User(email, firstName, lastName);
        user.setRoles(new ArrayList<>());
        user.setProfile(new UserProfile(user, firstName, lastName));
        UserRole userRole = new UserRole("ROLE_USER");
        userRole.setUser(user);
        user.getRoles().add(userRole);
        if (verified) {
            user.markEmailVerified(now);
        } else {
            user.setStatus(UserStatus.PENDING_VERIFICATION);
        }

        User saved = userService.save(user);
        AuthIdentity identity = new AuthIdentity(saved, AuthIdentity.LOCAL_PROVIDER, email);
        if (verified) {
            identity.setVerifiedAt(now);
        }
        authIdentityRepository.save(identity);
        if (rawPassword != null) {
            passwordCredentialRepository.save(new PasswordCredential(identity, passwordEncoder.encode(rawPassword)));
        }
        return saved;
    }
}
