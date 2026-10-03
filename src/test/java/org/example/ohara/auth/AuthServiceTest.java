package org.example.ohara.auth;

import org.example.ohara.config.JwtTokenProvider;
import org.example.ohara.user.User;
import org.example.ohara.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthenticationManager authenticationManager;
    private JwtTokenProvider jwtTokenProvider;
    private UserDetailsService userDetailsService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authenticationManager = mock(AuthenticationManager.class);
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", "ohara-local-jwt-secret-key-should-be-at-least-32-chars");
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenValidityMs", 3600000L);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenValidityMs", 604800000L);
        jwtTokenProvider.init();
        userDetailsService = username -> org.springframework.security.core.userdetails.User.withUsername(username)
            .password("encoded-password")
            .authorities("ROLE_USER")
            .build();

        authService = new AuthService(
            userRepository,
            passwordEncoder,
            authenticationManager,
            jwtTokenProvider,
            userDetailsService
        );
    }

    @Test
    void registerCreatesUserAndReturnsTokens() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1234")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = authService.register(new RegisterRequest("Ada", "Lovelace", "user@example.com", "secret1234"));

        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }
}
