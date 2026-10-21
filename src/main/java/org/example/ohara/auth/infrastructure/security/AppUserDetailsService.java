package org.example.ohara.auth.infrastructure.security;

import org.example.ohara.auth.domain.AuthIdentity;
import org.example.ohara.auth.domain.PasswordCredential;
import org.example.ohara.auth.infrastructure.persistence.PasswordCredentialRepository;
import org.example.ohara.user.application.UserService;
import org.example.ohara.user.domain.User;
import org.example.ohara.user.domain.UserRole;
import org.example.ohara.user.domain.UserStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** Loads a user for password login. Username is the email; locked or inactive accounts can't authenticate. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserService userService;
    private final PasswordCredentialRepository passwordCredentialRepository;

    public AppUserDetailsService(UserService userService, PasswordCredentialRepository passwordCredentialRepository) {
        this.userService = userService;
        this.passwordCredentialRepository = passwordCredentialRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        String email = username.toLowerCase(Locale.ROOT);
        User user = userService.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        Optional<PasswordCredential> credential = passwordCredentialRepository
            .findByAuthIdentityProviderAndAuthIdentityIdentifier(AuthIdentity.LOCAL_PROVIDER, email);
        String password = credential.map(PasswordCredential::getEncodedPassword).orElse("");
        boolean locked = user.getStatus() == UserStatus.SUSPENDED
            || credential.map(c -> c.isLocked(Instant.now())).orElse(false);

        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
            .password(password)
            .authorities(user.getRoles().stream()
                .map(UserRole::getName)
                .map(name -> name.startsWith("ROLE_") ? name : "ROLE_" + name)
                .map(SimpleGrantedAuthority::new)
                .toList())
            .accountLocked(locked)
            .disabled(!user.canSignIn())
            .build();
    }
}
