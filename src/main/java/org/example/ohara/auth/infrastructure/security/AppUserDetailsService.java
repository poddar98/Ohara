package org.example.ohara.auth.infrastructure.security;

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
        User user = userService.findByEmail(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        String password = passwordCredentialRepository.findByAuthIdentityUserEmail(username)
            .map(PasswordCredential::getEncodedPassword)
            .orElse("");

        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
            .password(password)
            .authorities(user.getRoles().stream()
                .map(UserRole::getName)
                .map(name -> name.startsWith("ROLE_") ? name : "ROLE_" + name)
                .map(SimpleGrantedAuthority::new)
                .toList())
            .accountLocked(user.getStatus() != UserStatus.ACTIVE)
            .disabled(user.getStatus() != UserStatus.ACTIVE)
            .build();
    }
}
