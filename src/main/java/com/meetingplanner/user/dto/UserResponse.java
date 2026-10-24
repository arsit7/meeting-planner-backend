package com.meetingplanner.user.dto;

import com.meetingplanner.user.User;

import java.time.Instant;

/**
 * Public view of a user. {@code avatarUrl} is an authenticated API path (null when no avatar);
 * the version parameter changes on every upload so clients never show a stale cached image.
 */
public record UserResponse(Long id, String name, String email, String avatarUrl, Instant createdAt) {

    public static final String AVATAR_PATH = "/api/users/me/avatar";

    public static UserResponse from(User user) {
        String avatarUrl = user.getProfileImagePath() == null
                ? null
                : AVATAR_PATH + "?v=" + user.getProfileImagePath().substring(0, 8);
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), avatarUrl, user.getCreatedAt());
    }
}
