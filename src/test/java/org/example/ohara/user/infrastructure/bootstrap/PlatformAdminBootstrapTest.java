package org.example.ohara.user.infrastructure.bootstrap;

import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformAdminBootstrapTest {

    private final UserRepository users = mock(UserRepository.class);

    private User user() {
        User user = new User("ops@example.com", "Ops", "Admin");
        UserRole role = new UserRole("ROLE_USER");
        role.setUser(user);
        user.getRoles().add(role);
        return user;
    }

    private long adminRoles(User user) {
        return user.getRoles().stream().filter(r -> r.getName().equals(PlatformAdminBootstrap.ROLE)).count();
    }

    @Test
    void grantsPlatformAdminOnceToTheConfiguredAccount() {
        User user = user();
        when(users.findByEmailIgnoreCase("ops@example.com")).thenReturn(Optional.of(user));
        PlatformAdminBootstrap bootstrap = new PlatformAdminBootstrap(users, " ops@example.com ");

        bootstrap.run(null);
        bootstrap.run(null);

        assertThat(adminRoles(user)).isEqualTo(1);
    }

    @Test
    void doesNothingWhenNotConfigured() {
        new PlatformAdminBootstrap(users, "").run(null);

        verifyNoInteractions(users);
    }

    @Test
    void unknownAccountIsLoggedNotCreated() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        new PlatformAdminBootstrap(users, "missing@example.com").run(null);
    }
}
