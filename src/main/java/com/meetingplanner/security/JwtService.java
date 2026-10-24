package com.meetingplanner.security;

import com.meetingplanner.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates HS256-signed JWTs. The subject is the user's numeric id.
 */
@Service
public class JwtService {

    static final int MIN_SECRET_BYTES = 32;
    private static final String ISSUER = "meeting-planner";

    private final SecretKey key;
    private final Duration lifetime;
    private final Clock clock;

    @Autowired
    public JwtService(AppProperties properties) {
        this(properties.jwt().secret(), Duration.ofMinutes(properties.jwt().expirationMinutes()), Clock.systemUTC());
    }

    JwtService(String secret, Duration lifetime, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing secret is not configured. Set the JWT_SECRET environment variable "
                            + "(at least " + MIN_SECRET_BYTES + " characters) or app.jwt.secret in "
                            + "config/application-local.properties. See README for details.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT signing secret is too short: it must be at least " + MIN_SECRET_BYTES + " bytes.");
        }
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalStateException("app.jwt.expiration-minutes must be positive.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public String issueToken(Long userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long getLifetimeSeconds() {
        return lifetime.toSeconds();
    }

    /**
     * @return the user id if the token is well-formed, correctly signed, unexpired and issued by us.
     */
    public Optional<Long> validateAndGetUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
