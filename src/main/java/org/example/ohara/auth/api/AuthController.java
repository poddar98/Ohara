package org.example.ohara.auth.api;

import jakarta.validation.Valid;
import org.example.ohara.auth.api.dto.AuthResponse;
import org.example.ohara.auth.api.dto.EmailRequest;
import org.example.ohara.auth.api.dto.LoginRequest;
import org.example.ohara.auth.api.dto.RegisterRequest;
import org.example.ohara.auth.api.dto.RegistrationResponse;
import org.example.ohara.auth.api.dto.ResetPasswordRequest;
import org.example.ohara.auth.api.dto.TokenRequest;
import org.example.ohara.auth.application.AccountRecoveryService;
import org.example.ohara.auth.application.AuthService;
import org.example.ohara.auth.application.IssuedTokens;
import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthService authService;
    private final AccountRecoveryService accountRecoveryService;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          AccountRecoveryService accountRecoveryService,
                          @Value("${auth.refresh-cookie.secure:true}") boolean cookieSecure) {
        this.authService = authService;
        this.accountRecoveryService = accountRecoveryService;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.accepted()
            .body(RegistrationResponse.verificationRequired(request.email().trim().toLowerCase(Locale.ROOT)));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody TokenRequest request) {
        return withRefreshCookie(accountRecoveryService.verifyEmail(request.token()));
    }

    @PostMapping("/verify-email/resend")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody EmailRequest request) {
        accountRecoveryService.resendVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new ApiException.Unauthenticated("refresh_token_missing", "Refresh token cookie is missing");
        }
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        return clearedCookie();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal CurrentActor actor) {
        authService.logoutAll(actor.userId());
        return clearedCookie();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody EmailRequest request) {
        accountRecoveryService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        accountRecoveryService.resetPassword(request.token(), request.newPassword());
        return clearedCookie();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(IssuedTokens tokens) {
        Duration maxAge = Duration.between(Instant.now(), tokens.refreshExpiresAt());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), maxAge).toString())
            .body(new AuthResponse(tokens.accessToken(), "Bearer", tokens.email()));
    }

    private ResponseEntity<Void> clearedCookie() {
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
            .build();
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path(COOKIE_PATH)
            .maxAge(maxAge)
            .build();
    }
}
