package org.example.ohara.user.infrastructure.bootstrap;

import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.infrastructure.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants ROLE_PLATFORM_ADMIN to the account named by {@code ohara.bootstrap.platform-admin-email} at start-up.
 * This is how the first platform admin is created; the account must already exist (register and verify first).
 */
@Component
class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);
    static final String ROLE = "ROLE_PLATFORM_ADMIN";

    private final UserRepository users;
    private final String email;

    PlatformAdminBootstrap(UserRepository users, @Value("${ohara.bootstrap.platform-admin-email:}") String email) {
        this.users = users;
        this.email = email;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank()) {
            return;
        }
        users.findByEmailIgnoreCase(email.trim()).ifPresentOrElse(this::grant,
            () -> log.warn("Platform admin bootstrap: no account for {}; register it first", email));
    }

    private void grant(User user) {
        boolean alreadyAdmin = user.getRoles().stream().anyMatch(role -> ROLE.equals(role.getName()));
        if (alreadyAdmin) {
            return;
        }
        UserRole role = new UserRole(ROLE);
        role.setUser(user);
        user.getRoles().add(role);
        log.info("Platform admin bootstrap: granted {} to {}", ROLE, user.getEmail());
    }
}
