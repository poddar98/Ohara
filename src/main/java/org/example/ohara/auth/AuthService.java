package org.example.ohara.auth;

import org.example.ohara.auth_identity.AuthIdentity;
import org.example.ohara.config.JwtTokenProvider;
import org.example.ohara.password_credential.PasswordCredential;
import org.example.ohara.user.User;
import org.example.ohara.user.UserRepository;
import org.example.ohara.user_profile.UserProfile;
import org.example.ohara.user_role.UserRole;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            UserDetailsService userDetailsService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("User already exists");
        }

        User user = new User(email, request.firstName(), request.lastName());
        user.setRoles(new ArrayList<>());
        

        UserProfile profile = new UserProfile(user, request.firstName(), request.lastName());
        user.setProfile(profile);

        AuthIdentity authIdentity = new AuthIdentity(user, "local", email);
        user.getAuthIdentities().add(authIdentity);

        UserRole userRole = new UserRole("ROLE_USER");
        userRole.setUser(user);
        user.getRoles().add(userRole);

        String encodedPassword = passwordEncoder.encode(request.password());
        PasswordCredential passwordCredential = new PasswordCredential(authIdentity, encodedPassword);
        authIdentity.setPasswordCredential(passwordCredential);

        userRepository.save(user);

        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken, "Bearer", user.getEmail());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email().toLowerCase(), request.password())
        );

        if (!authentication.isAuthenticated()) {
            throw new IllegalArgumentException("Invalid credentials");
        }

        User user = userRepository.findByEmail(request.email().toLowerCase())
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setLastLoginAt(LocalDateTime.now());

        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken, "Bearer", user.getEmail());
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        String refreshToken = request.refreshToken();
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String email = jwtTokenProvider.getUsernameFromToken(refreshToken);
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user);
        return new AuthResponse(accessToken, newRefreshToken, "Bearer", user.getEmail());
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash refresh token", ex);
        }
    }
}
