package com.meetingplanner.meeting.dto;

import com.meetingplanner.meeting.Participant;

import java.time.Instant;

public record ParticipantResponse(Long id, String name, String email, Instant createdAt) {

    public static ParticipantResponse from(Participant participant) {
        return new ParticipantResponse(participant.getId(), participant.getName(), participant.getEmail(),
                participant.getCreatedAt());
    }
}
