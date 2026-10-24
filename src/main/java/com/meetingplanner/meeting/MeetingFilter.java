package com.meetingplanner.meeting;

import com.meetingplanner.common.ApiException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** The {@code filter} query parameter of {@code GET /api/meetings}. */
public enum MeetingFilter {
    ALL, UPCOMING, COMPLETED, ORGANIZED, INVITED;

    static MeetingFilter fromParam(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("filter must be one of: " + Arrays.stream(values())
                    .map(f -> f.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", ")));
        }
    }
}
