package org.example.ohara.auth.application;

import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.domain.OneTimeToken;
import org.example.ohara.auth.domain.OneTimeToken.Purpose;
import org.example.ohara.auth.domain.PasswordCredential;
import org.example.ohara.auth.infrastructure.persistence.AuthIdentityRepository;
import org.example.ohara.auth.infrastructure.persistence.OneTimeTokenRepository;
import org.example.ohara.auth.infrastructure.persistence.PasswordCredentialRepository;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Email verification and password reset. Request endpoints never reveal whether an account exists;
 * the email (or its absence) is the only signal.
 */
@Service
public class AccountRecoveryService {

    static final Duration VERIFICATION_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final Duration SET_PASSWORD_TTL = Duration.ofHours(72);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private static final Set<UserStatus> RESETTABLE =
        EnumSet.of(UserStatus.ACTIVE, UserStatus.PENDING_VERIFICATION, UserStatus.DEACTIVATED);

    private final UserService userService;
    private final OneTimeTokenRepository oneTimeTokenRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final AuthEmails emails;

    public AccountRecoveryService(
            UserService userService,
            OneTimeTokenRepository oneTimeTokenRepository,
            AuthIdentityRepository authIdentityRepository,
            PasswordCredentialRepository passwordCredentialRepository,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            AuthEmails emails) {
        this.userService = userService;
        this.oneTimeTokenRepository = oneTimeTokenRepository;
        this.authIdentityRepository = authIdentityRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.emails = emails;
    }

    /** Called inside the registration transaction. */
    void sendVerification(User user, Instant now) {
        String raw = issue(user, Purpose.EMAIL_VERIFICATION, VERIFICATION_TTL, now);
        emails.verification(user.getEmail(), raw);
    }

    /** First password for an admin-created account. Uses the reset flow, so using the link also verifies the email. */
    void sendSetPassword(User user, Instant now) {
        String raw = issue(user, Purpose.PASSWORD_RESET, SET_PASSWORD_TTL, now);
        emails.setPassword(user.getEmail(), raw);
    }

    @Transactional
    public void resendVerification(String email) {
        Instant now = Instant.now();
        userService.findByEmail(normalize(email))
            .filter(user -> user.getStatus() == UserStatus.PENDING_VERIFICATION)
            .filter(user -> !sentRecently(user, Purpose.EMAIL_VERIFICATION, now))
            .ifPresent(user -> sendVerification(user, now));
    }

    @Transactional
    public IssuedTokens verifyEmail(String rawToken) {
        Instant now = Instant.now();
        OneTimeToken token = consume(rawToken, Purpose.EMAIL_VERIFICATION, now, "verification_token");
        User user = token.getUser();
        if (!user.canSignIn()) {
            throw new ApiException.Forbidden("account_inactive", "Account is not active");
        }
        user.markEmailVerified(now);
        localIdentity(user).ifPresent(identity -> identity.setVerifiedAt(now));
        return tokenIssuer.issue(user);
    }

    @Transactional
    public void requestPasswordReset(String email) {
        Instant now = Instant.now();
        userService.findByEmail(normalize(email))
            .filter(user -> RESETTABLE.contains(user.getStatus()))
            .filter(user -> !sentRecently(user, Purpose.PASSWORD_RESET, now))
            .ifPresent(user -> emails.passwordReset(user.getEmail(), issue(user, Purpose.PASSWORD_RESET, RESET_TTL, now)));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = Instant.now();
        OneTimeToken token = consume(rawToken, Purpose.PASSWORD_RESET, now, "reset_token");
        User user = token.getUser();
        if (!RESETTABLE.contains(user.getStatus())) {
            throw new ApiException.Forbidden("account_inactive", "Account is not active");
        }

        AuthIdentity identity = localIdentity(user)
            .orElseGet(() -> authIdentityRepository.save(new AuthIdentity(user, AuthIdentity.LOCAL_PROVIDER, user.getEmail())));
        String encoded = passwordEncoder.encode(newPassword);
        passwordCredentialRepository.findByAuthIdentityProviderAndAuthIdentityIdentifier(AuthIdentity.LOCAL_PROVIDER, user.getEmail())
            .ifPresentOrElse(
                credential -> credential.changePassword(encoded, now),
                () -> passwordCredentialRepository.save(new PasswordCredential(identity, encoded)));

        // The link reached the inbox, so the email is proven.
        user.markEmailVerified(now);
        identity.setVerifiedAt(now);
        tokenIssuer.revokeAllSessions(user, now);
        emails.passwordChanged(user.getEmail());
    }

    private String issue(User user, Purpose purpose, Duration ttl, Instant now) {
        oneTimeTokenRepository.findByUserAndPurposeAndConsumedAtIsNull(user, purpose).forEach(old -> old.consume(now));
        String raw = SecretTokens.generate();
        oneTimeTokenRepository.save(new OneTimeToken(user, purpose, SecretTokens.sha256(raw), now.plus(ttl), now));
        return raw;
    }

    private boolean sentRecently(User user, Purpose purpose, Instant now) {
        return oneTimeTokenRepository.findByUserAndPurposeAndConsumedAtIsNull(user, purpose).stream()
            .anyMatch(token -> token.getCreatedAt().isAfter(now.minus(RESEND_COOLDOWN)));
    }

    private OneTimeToken consume(String rawToken, Purpose purpose, Instant now, String codePrefix) {
        OneTimeToken token = oneTimeTokenRepository.findByTokenHashAndPurpose(SecretTokens.sha256(rawToken), purpose)
            .orElseThrow(() -> new ApiException.BadRequest(codePrefix + "_invalid", "The link is invalid"));
        if (!token.isUsable(now)) {
            throw new ApiException.Gone(codePrefix + "_expired", "The link has expired or was already used");
        }
        token.consume(now);
        return token;
    }

    private Optional<AuthIdentity> localIdentity(User user) {
        return authIdentityRepository.findByUserAndProvider(user, AuthIdentity.LOCAL_PROVIDER);
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
