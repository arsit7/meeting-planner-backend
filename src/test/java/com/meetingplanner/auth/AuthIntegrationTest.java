package com.meetingplanner.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.meetingplanner.IntegrationTestSupport;
import com.meetingplanner.user.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTestSupport {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Test
    void signupCreatesAccountWithBcryptHashAndReturnsUsableToken() throws Exception {
        JsonNode response = signup("Alice", "Alice@Example.com", "password123");

        assertThat(response.get("token").asText()).isNotBlank();
        assertThat(response.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.get("user").get("email").asText()).isEqualTo("alice@example.com");
        assertThat(response.get("user").has("passwordHash")).isFalse();

        User stored = userRepository.findByEmail("alice@example.com").orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo("password123").matches("^\\$2[aby]\\$10\\$.{53}$");

        mockMvc.perform(withToken(get("/api/users/me"), response.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void duplicateEmailIsRejectedWithConflict() throws Exception {
        signup("Alice", "alice@example.com", "password123");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Other", "email", "ALICE@example.com", "password", "password456"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists"));
    }

    @Test
    void signupValidatesInput() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "", "email", "not-an-email", "password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void loginWithValidCredentialsReturnsToken() throws Exception {
        signup("Alice", "alice@example.com", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@example.com", "password", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("Alice"));
    }

    @Test
    void loginWithInvalidCredentialsIsUnauthorized() throws Exception {
        signup("Alice", "alice@example.com", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "alice@example.com", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "ghost@example.com", "password", "password123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void protectedEndpointsRejectMissingToken() throws Exception {
        mockMvc.perform(get("/api/meetings")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/meetings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedOrForgedTokenIsRejected() throws Exception {
        mockMvc.perform(withToken(get("/api/meetings"), "not.a.jwt")).andExpect(status().isUnauthorized());

        String forged = Jwts.builder().issuer("meeting-planner").subject("1")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor("an-attacker-chosen-secret-of-32-bytes!!".getBytes(StandardCharsets.UTF_8)))
                .compact();
        mockMvc.perform(withToken(get("/api/meetings"), forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JsonNode response = signup("Alice", "alice@example.com", "password123");
        String userId = response.get("user").get("id").asText();
        String expired = Jwts.builder().issuer("meeting-planner").subject(userId)
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(withToken(get("/api/users/me"), expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenOfDeletedUserIsRejected() throws Exception {
        String token = tokenFor("Alice", "alice@example.com");
        userRepository.deleteAll();

        mockMvc.perform(withToken(get("/api/users/me"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/api/meetings"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void corsAllowsOnlyConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/meetings")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mockMvc.perform(options("/api/meetings")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
