package com.meetingplanner.auth;

import com.meetingplanner.auth.dto.AuthResponse;
import com.meetingplanner.auth.dto.LoginRequest;
import com.meetingplanner.auth.dto.SignupRequest;
import com.meetingplanner.common.ApiException;
import com.meetingplanner.common.Emails;
import com.meetingplanner.security.JwtService;
import com.meetingplanner.user.User;
import com.meetingplanner.user.UserRepository;
import com.meetingplanner.user.dto.UserResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Checked when the e-mail is unknown, so both failed-login paths take one BCrypt comparison. */
    private final String unknownUserHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        String email = Emails.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = new User(request.name().trim(), email, passwordEncoder.encode(request.password()));
        user = userRepository.save(user);
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Unknown e-mail and wrong password get the same message and the same BCrypt cost, so neither the
        // response nor its timing reveals which e-mails have accounts.
        User user = userRepository.findByEmail(Emails.normalize(request.email())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : unknownUserHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !passwordMatches) {
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        return new AuthResponse(jwtService.issueToken(user.getId()), "Bearer",
                jwtService.getLifetimeSeconds(), UserResponse.from(user));
    }
}
