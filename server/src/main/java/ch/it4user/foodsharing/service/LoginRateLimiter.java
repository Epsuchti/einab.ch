package ch.it4user.foodsharing.service;

import ch.it4user.foodsharing.repository.LoginTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Limits login link requests, so nobody can flood a Foodsharing account with login messages. The per-account limit
 * counts the stored login tokens (survives restarts); the per-IP limit is kept in memory.
 */
@Service
public class LoginRateLimiter {

    private final LoginTokenRepository loginTokenRepository;
    private final AppProperties appProperties;
    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

    public LoginRateLimiter(LoginTokenRepository loginTokenRepository, AppProperties appProperties) {
        this.loginTokenRepository = loginTokenRepository;
        this.appProperties = appProperties;
    }

    public void check(String foodsharingId, String clientIp) {
        AppProperties.Auth auth = appProperties.getAuth();
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofMinutes(auth.getLoginRequestWindowMinutes()));
        if (clientIp != null && !allowIp(clientIp, now, windowStart, auth.getMaxLoginRequestsPerIp())) {
            throw tooManyRequests();
        }
        if (loginTokenRepository.countByFoodsharingIdIgnoreCaseAndCreatedAtAfter(foodsharingId, windowStart)
                >= auth.getMaxLoginRequestsPerAccount()) {
            throw tooManyRequests();
        }
    }

    private boolean allowIp(String clientIp, Instant now, Instant windowStart, int limit) {
        boolean[] allowed = {false};
        requestsByIp.compute(clientIp, (ip, requests) -> {
            Deque<Instant> recent = requests == null ? new ArrayDeque<>() : requests;
            while (!recent.isEmpty() && recent.peekFirst().isBefore(windowStart)) {
                recent.pollFirst();
            }
            if (recent.size() < limit) {
                recent.addLast(now);
                allowed[0] = true;
            }
            return recent;
        });
        if (requestsByIp.size() > 10_000) {
            requestsByIp.values().removeIf(requests -> requests.isEmpty() || requests.peekLast().isBefore(windowStart));
        }
        return allowed[0];
    }

    private static ApiException tooManyRequests() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.TOO_MANY_LOGIN_REQUESTS);
    }
}
