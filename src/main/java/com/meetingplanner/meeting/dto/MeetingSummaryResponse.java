package com.meetingplanner.meeting.dto;

import com.meetingplanner.meeting.Meeting;

import java.time.Instant;

/** Compact list item for the dashboard. */
public record MeetingSummaryResponse(
        Long id,
        String title,
        Instant startTime,
        Instant endTime,
        String organizerName,
        boolean owner,
        int participantCount) {

    public static MeetingSummaryResponse from(Meeting meeting, Long currentUserId) {
        return new MeetingSummaryResponse(
                meeting.getId(),
                meeting.getTitle(),
                meeting.getStartTime(),
                meeting.getEndTime(),
                meeting.getCreatedBy().getName(),
                meeting.isOwnedBy(currentUserId),
                meeting.getParticipants().size());
    }
}
