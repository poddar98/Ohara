package org.example.ohara.user.application;

import org.example.ohara.common.web.ApiException;
import org.example.ohara.user.domain.UserConsent;
import org.example.ohara.user.infrastructure.persistence.UserConsentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records consent decisions (terms, privacy notice, marketing email) as evidence: who agreed to which version, when,
 * and through which flow. Terms and privacy can't be withdrawn here — withdrawing them means deleting the account.
 */
@Service
public class ConsentService {

    public record ConsentState(UserConsent.Type type, boolean granted, String version, boolean current) {
    }

    private final UserConsentRepository consents;
    private final String termsVersion;
    private final String privacyVersion;

    public ConsentService(UserConsentRepository consents,
                          @Value("${ohara.legal.terms-version:2026-10-01}") String termsVersion,
                          @Value("${ohara.legal.privacy-version:2026-10-01}") String privacyVersion) {
        this.consents = consents;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    /** Terms and privacy notice accepted, plus the marketing choice made on the sign-up form. */
    @Transactional
    public void recordSignUp(UUID userId, boolean marketingEmails, UserConsent.Source source) {
        consents.saveAll(List.of(
            new UserConsent(userId, UserConsent.Type.TERMS, termsVersion, true, source),
            new UserConsent(userId, UserConsent.Type.PRIVACY, privacyVersion, true, source),
            new UserConsent(userId, UserConsent.Type.MARKETING_EMAIL, "n/a", marketingEmails, source)));
    }

    @Transactional
    public List<ConsentState> update(UUID userId, Boolean acceptTerms, Boolean marketingEmails) {
        if (Boolean.FALSE.equals(acceptTerms)) {
            throw new ApiException.BadRequest("terms_withdrawal_not_supported",
                "The terms can't be withdrawn while keeping the account; request account deletion instead");
        }
        if (Boolean.TRUE.equals(acceptTerms)) {
            consents.save(new UserConsent(userId, UserConsent.Type.TERMS, termsVersion, true, UserConsent.Source.SETTINGS));
            consents.save(new UserConsent(userId, UserConsent.Type.PRIVACY, privacyVersion, true, UserConsent.Source.SETTINGS));
        }
        if (marketingEmails != null) {
            consents.save(new UserConsent(userId, UserConsent.Type.MARKETING_EMAIL, "n/a", marketingEmails,
                UserConsent.Source.SETTINGS));
        }
        consents.flush();
        return current(userId);
    }

    @Transactional(readOnly = true)
    public List<ConsentState> current(UUID userId) {
        Map<UserConsent.Type, UserConsent> latest = new EnumMap<>(UserConsent.Type.class);
        consents.findCurrent(userId).stream()
            .sorted(Comparator.comparing(UserConsent::getRecordedAt))
            .forEach(c -> latest.put(c.getType(), c));
        return List.of(UserConsent.Type.values()).stream()
            .map(type -> {
                UserConsent c = latest.get(type);
                if (c == null) {
                    return new ConsentState(type, false, null, false);
                }
                return new ConsentState(type, c.isGranted(), c.getVersion(), isCurrentVersion(c));
            })
            .toList();
    }

    /** True when the latest terms and privacy decisions accept the versions currently in force. */
    @Transactional(readOnly = true)
    public boolean hasAcceptedCurrentTerms(UUID userId) {
        return current(userId).stream()
            .filter(s -> s.type() == UserConsent.Type.TERMS || s.type() == UserConsent.Type.PRIVACY)
            .allMatch(s -> s.granted() && s.current());
    }

    private boolean isCurrentVersion(UserConsent consent) {
        return switch (consent.getType()) {
            case TERMS -> termsVersion.equals(consent.getVersion());
            case PRIVACY -> privacyVersion.equals(consent.getVersion());
            case MARKETING_EMAIL -> true;
        };
    }
}
