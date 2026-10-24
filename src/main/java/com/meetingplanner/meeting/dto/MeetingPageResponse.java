package com.meetingplanner.meeting.dto;

import java.util.List;

/**
 * One page of the meeting list. {@code page} is zero-based. {@code counts} gives the number of meetings in
 * each filter tab for the current search, so the client can label its tabs without extra requests.
 */
public record MeetingPageResponse(
        List<MeetingSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Counts counts) {

    public record Counts(long all, long upcoming, long completed, long organized, long invited) {
    }
}
