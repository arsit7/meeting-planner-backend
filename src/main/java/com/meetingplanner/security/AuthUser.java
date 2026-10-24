package com.meetingplanner.security;

/**
 * The authenticated principal placed in the security context after JWT validation.
 * Controllers obtain the current user exclusively from here, never from request data.
 */
public record AuthUser(Long id, String email) {
}
