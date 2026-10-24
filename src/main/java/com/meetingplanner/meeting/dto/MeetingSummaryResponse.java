package com.meetingplanner.meeting.dto;

import com.meetingplanner.meeting.Meeting;

import java.time.Instant;

/** Compact list item for the dashboard. {@code completed} is true once the meeting has ended. */
public record MeetingSummaryResponse(
        Long id,
        String title,
        Instant startTime,
        Instant endTime,
        String organizerName,
        boolean owner,
        boolean completed,
        int participantCount) {

    public static MeetingSummaryResponse from(Meeting meeting, Long currentUserId, Instant now) {
        return new MeetingSummaryResponse(
                meeting.getId(),
                meeting.getTitle(),
                meeting.getStartTime(),
                meeting.getEndTime(),
                meeting.getCreatedBy().getName(),
                meeting.isOwnedBy(currentUserId),
                meeting.hasEnded(now),
                meeting.getParticipants().size());
    }
}
