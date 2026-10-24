package com.meetingplanner.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-with-at-least-32-bytes!";
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final JwtService jwtService = new JwtService(SECRET, Duration.ofMinutes(60), fixedClock(NOW));

    @Test
    void issuedTokenValidatesToSameUserId() {
        String token = jwtService.issueToken(42L);

        assertThat(jwtService.validateAndGetUserId(token)).contains(42L);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = jwtService.issueToken(42L);
        JwtService later = new JwtService(SECRET, Duration.ofMinutes(60), fixedClock(NOW.plus(Duration.ofMinutes(61))));

        assertThat(later.validateAndGetUserId(token)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issueToken(42L);
        String[] parts = token.split("\\.");
        // Swap the payload for one claiming a different subject, keeping the original signature.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"meeting-planner\",\"sub\":\"1\",\"exp\":9999999999}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(jwtService.validateAndGetUserId(forged)).isEmpty();
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtService other = new JwtService("a-completely-different-secret-of-32-bytes+", Duration.ofMinutes(60),
                fixedClock(NOW));

        assertThat(jwtService.validateAndGetUserId(other.issueToken(42L))).isEmpty();
    }

    @Test
    void garbageTokenIsRejected() {
        assertThat(jwtService.validateAndGetUserId("not-a-jwt")).isEmpty();
        assertThat(jwtService.validateAndGetUserId("")).isEmpty();
    }

    @Test
    void missingSecretFailsFastWithClearMessage() {
        assertThatThrownBy(() -> new JwtService("", Duration.ofMinutes(60), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> new JwtService("too-short", Duration.ofMinutes(60), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
