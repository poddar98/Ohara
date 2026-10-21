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

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
}
