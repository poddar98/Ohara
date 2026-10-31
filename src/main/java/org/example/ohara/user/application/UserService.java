package org.example.ohara.user.application;

import org.example.ohara.common.web.ApiException;
import org.example.ohara.user.api.dto.UserResponse;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final ConsentService consentService;
    private final AccountEvents events;

    public UserService(UserRepository userRepository, ConsentService consentService, AccountEvents events) {
        this.userRepository = userRepository;
        this.consentService = consentService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException.NotFound("user_not_found", "User not found"));

        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getStatus().name(),
            user.isEmailVerified(),
            consentService.hasAcceptedCurrentTerms(user.getId()),
            user.getRoles().stream().map(UserRole::getName).toList()
        );
    }

    @Transactional(readOnly = true)
    public Optional<UserAuthState> findAuthState(UUID userId) {
        return userRepository.findById(userId).map(user -> new UserAuthState(user.isActive(), user.canSignIn(), user.getAuthVersion()));
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(UUID userId) {
        return userRepository.findById(userId);
    }

    @Transactional(readOnly = true)
    public java.util.Map<UUID, User> findByIds(java.util.Collection<UUID> ids) {
        return userRepository.findAllById(ids).stream()
            .collect(java.util.stream.Collectors.toMap(User::getId, java.util.function.Function.identity()));
    }

    /** Used by other modules (auth) that need to look up or create users without touching the repository. */
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email);
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmailIgnoreCase(email);
    }

    @Transactional
    public User save(User user) {
        return userRepository.save(user);
    }

    /**
     * Anonymizes the account (docs 04 §3.10): scrubs this module's data, records consent withdrawal, then lets every
     * other module scrub theirs in the same transaction ({@link AccountEvents.Anonymizing}). Returns the former email.
     */
    @Transactional
    public String anonymize(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException.NotFound("user_not_found", "User not found"));
        String formerEmail = user.getEmail();
        user.anonymize(java.time.Instant.now());
        userRepository.saveAndFlush(user);
        consentService.recordWithdrawal(userId);
        events.anonymizing(userId, formerEmail);
        return formerEmail;
    }
}
