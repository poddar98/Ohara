package org.example.ohara.auth.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.ohara.auth.infrastructure.jwt.JwtTokenProvider;
import org.example.ohara.auth.infrastructure.jwt.JwtTokenProvider.AccessClaims;
import org.example.ohara.user.application.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Authenticates bearer access tokens. Besides the signature, the token's {@code ver} must equal the user's current
 * auth_version and the account must be able to sign in, so suspensions and session revocations apply immediately.
 * Only full-scope tokens of ACTIVE users get {@value #FULL_SCOPE}; unverified sessions are limited (see SecurityConfig).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String FULL_SCOPE = "SCOPE_full";

    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, UserService userService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtTokenProvider.parseAccessToken(header.substring(7)).ifPresent(claims ->
                userService.findAuthState(claims.actor().userId())
                    .filter(state -> state.canSignIn() && state.authVersion() == claims.authVersion())
                    .ifPresent(state -> authenticate(claims, claims.fullScope() && state.active())));
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(AccessClaims claims, boolean fullScope) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        claims.actor().platformRoles().forEach(role -> authorities.add(new SimpleGrantedAuthority(role)));
        if (fullScope) {
            authorities.add(new SimpleGrantedAuthority(FULL_SCOPE));
        }
        var authentication = new UsernamePasswordAuthenticationToken(claims.actor(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
