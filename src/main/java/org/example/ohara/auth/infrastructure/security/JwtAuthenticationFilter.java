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

/**
 * Authenticates bearer access tokens. Besides the signature, the token's {@code ver} must equal the user's current
 * auth_version and the account must be ACTIVE, so suspensions and session revocations apply immediately.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

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
            jwtTokenProvider.parseAccessToken(header.substring(7))
                .filter(this::isCurrent)
                .ifPresent(this::authenticate);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isCurrent(AccessClaims claims) {
        return userService.findAuthState(claims.actor().userId())
            .filter(state -> state.active() && state.authVersion() == claims.authVersion())
            .isPresent();
    }

    private void authenticate(AccessClaims claims) {
        var authorities = claims.actor().platformRoles().stream().map(SimpleGrantedAuthority::new).toList();
        var authentication = new UsernamePasswordAuthenticationToken(claims.actor(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
