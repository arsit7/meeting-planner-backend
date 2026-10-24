package com.meetingplanner.meeting.dto;

import com.meetingplanner.meeting.Meeting;
import com.meetingplanner.user.User;

import java.time.Instant;
import java.util.List;

/**
 * Full meeting details. {@code owner} tells the client whether the current user organizes the meeting;
 * {@code completed} is true once it has ended, after which nobody can change it.
 */
public record MeetingResponse(
        Long id,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        Organizer organizer,
        boolean owner,
        boolean completed,
        List<ParticipantResponse> participants,
        Instant createdAt,
        Instant updatedAt) {

    public record Organizer(Long id, String name, String email) {
    }

    public static MeetingResponse from(Meeting meeting, Long currentUserId, Instant now) {
        User creator = meeting.getCreatedBy();
        return new MeetingResponse(
                meeting.getId(),
                meeting.getTitle(),
                meeting.getDescription(),
                meeting.getStartTime(),
                meeting.getEndTime(),
                new Organizer(creator.getId(), creator.getName(), creator.getEmail()),
                meeting.isOwnedBy(currentUserId),
                meeting.hasEnded(now),
                meeting.getParticipants().stream().map(ParticipantResponse::from).toList(),
                meeting.getCreatedAt(),
                meeting.getUpdatedAt());
    }
}
