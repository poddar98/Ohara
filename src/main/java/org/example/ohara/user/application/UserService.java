package org.example.ohara.user.application;

import org.example.ohara.user.api.dto.UserResponse;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse getByEmail(String email) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getFirstName(),
            user.getLastName(),
            user.getStatus().name(),
            user.getRoles().stream().map(UserRole::getName).toList()
        );
    }
}
