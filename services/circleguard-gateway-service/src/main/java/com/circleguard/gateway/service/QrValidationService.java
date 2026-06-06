package com.circleguard.gateway.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.security.Key;

@Service
@RequiredArgsConstructor
public class QrValidationService {
    private final StringRedisTemplate redisTemplate;

    @Value("${qr.secret}")
    private String qrSecret;

    private static final String STATUS_KEY_PREFIX = "user:status:";

    @CircuitBreaker(name = "auth-service", fallbackMethod = "validateTokenFallback")
    public ValidationResult validateToken(String token) {
        String anonymousId;
        try {
            Key key = Keys.hmacShaKeyFor(qrSecret.getBytes());
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            anonymousId = claims.getSubject();
        } catch (Exception e) {
            // Invalid/expired token is a client error, not a dependency failure:
            // resolve it here so it does not count against the circuit breaker.
            return new ValidationResult(false, "RED", "Invalid or Expired Token");
        }

        // Health status lookup against the status store. A failure here (store down,
        // timeout) propagates so the circuit breaker can open and shed load.
        String status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + anonymousId);

        if ("CONFIRMED".equals(status) || "SUSPECT".equals(status) || "PROBABLE".equals(status)) {
            return new ValidationResult(false, "RED", "Access Denied: Health Risk Detected");
        }
        return new ValidationResult(true, "GREEN", "Welcome to Campus");
    }

    @SuppressWarnings("unused")
    private ValidationResult validateTokenFallback(String token, Throwable t) {
        // Open circuit / dependency failure: fail closed (deny) so the gate stays safe.
        return new ValidationResult(false, "RED", "Validation temporarily unavailable, please retry");
    }

    public record ValidationResult(boolean valid, String status, String message) {}
}
