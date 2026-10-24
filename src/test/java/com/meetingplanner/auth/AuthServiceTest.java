package com.meetingplanner.auth;

import com.meetingplanner.auth.dto.AuthResponse;
import com.meetingplanner.auth.dto.LoginRequest;
import com.meetingplanner.auth.dto.SignupRequest;
import com.meetingplanner.common.ApiException;
import com.meetingplanner.security.JwtService;
import com.meetingplanner.user.User;
import com.meetingplanner.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void signupStoresBcryptHashAndReturnsToken() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0), 1L));
        when(jwtService.issueToken(1L)).thenReturn("jwt-token");

        AuthResponse response = authService.signup(new SignupRequest(" Alice ", " Alice@Example.com ", "secret123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User user = saved.getValue();
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getName()).isEqualTo("Alice");
        assertThat(user.getPasswordHash()).isNotEqualTo("secret123").startsWith("$2a$");
        assertThat(passwordEncoder.matches("secret123", user.getPasswordHash())).isTrue();
        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.user().email()).isEqualTo("alice@example.com");
    }

    @Test
    void signupRejectsDuplicateEmailCaseInsensitively() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(new SignupRequest("Alice", "ALICE@example.com", "secret123")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() {
        User user = withId(new User("Alice", "alice@example.com", passwordEncoder.encode("secret123")), 7L);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(jwtService.issueToken(7L)).thenReturn("jwt-token");

        AuthResponse response = authService.login(new LoginRequest("alice@example.com", "secret123"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() {
        User user = withId(new User("Alice", "alice@example.com", passwordEncoder.encode("secret123")), 7L);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice@example.com", "wrong-password")))
                .isInstanceOf(ApiException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(jwtService, never()).issueToken(anyLong());
    }

    @Test
    void loginWithUnknownEmailGivesSameErrorAsWrongPassword() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "secret123")))
                .isInstanceOf(ApiException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS);
    }

    @Test
    void loginWithUnknownEmailStillRunsAPasswordCheck() {
        // Without this, an unknown e-mail would fail faster than a wrong password and reveal which accounts exist.
        PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));
        AuthService service = new AuthService(userRepository, encoder, jwtService);
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "secret123")))
                .hasMessage(AuthService.INVALID_CREDENTIALS);
        verify(encoder).matches(eq("secret123"), startsWith("$2a$"));
    }

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
