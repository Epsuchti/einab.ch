package ch.it4user.foodsharing.security;

import ch.it4user.foodsharing.domain.entity.AuthSession;
import ch.it4user.foodsharing.repository.AuthSessionRepository;
import ch.it4user.foodsharing.repository.UserRepository;
import ch.it4user.foodsharing.service.PermissionResolutionService;
import ch.it4user.foodsharing.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PermissionResolutionService permissionResolutionService;

    public BearerTokenAuthenticationFilter(AuthSessionRepository authSessionRepository,
                                           UserRepository userRepository,
                                           TokenService tokenService,
                                           PermissionResolutionService permissionResolutionService) {
        this.authSessionRepository = authSessionRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.permissionResolutionService = permissionResolutionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String rawToken = authorization.substring("Bearer ".length()).trim();
            authSessionRepository.findByTokenHash(tokenService.hash(rawToken))
                    .filter(session -> session.getExpiresAt().isAfter(Instant.now()))
                    .ifPresent(this::authenticate);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(AuthSession session) {
        var user = userRepository.findByFoodsharingIdIgnoreCaseAndActiveTrue(session.getFoodsharingId());
        if (user.isEmpty()) {
            return;
        }
        // Read from the user on every request, so permission changes apply without a new login.
        var authorities = permissionResolutionService.resolvePermissions(user.get()).stream()
                .map(permission -> new SimpleGrantedAuthority(permission.name()))
                .collect(Collectors.toList());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(session.getFoodsharingId(), session.getTokenHash(), authorities));
    }
}
