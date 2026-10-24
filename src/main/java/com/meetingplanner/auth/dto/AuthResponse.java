package com.meetingplanner.auth.dto;

import com.meetingplanner.user.dto.UserResponse;

public record AuthResponse(String token, String tokenType, long expiresInSeconds, UserResponse user) {
}
