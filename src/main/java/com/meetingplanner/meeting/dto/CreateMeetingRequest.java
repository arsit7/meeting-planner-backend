package com.meetingplanner.meeting.dto;

import com.meetingplanner.meeting.Meeting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

@ValidTimeRange
public record CreateMeetingRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 150, message = "Title must be at most 150 characters")
        String title,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description,

        @NotNull(message = "Start time is required")
        Instant startTime,

        @NotNull(message = "End time is required")
        Instant endTime,

        @Size(max = Meeting.MAX_PARTICIPANTS, message = "A meeting can have at most {max} participants")
        List<@Valid @NotNull ParticipantRequest> participants) implements TimeRange {

    public List<ParticipantRequest> participantsOrEmpty() {
        return participants == null ? List.of() : participants;
    }
}
