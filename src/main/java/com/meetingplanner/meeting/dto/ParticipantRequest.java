package com.meetingplanner.meeting.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ParticipantRequest(
        @NotBlank(message = "Participant name is required")
        @Size(max = 100, message = "Participant name must be at most 100 characters")
        String name,

        @NotBlank(message = "Participant email is required")
        @Email(message = "Participant email must be a valid email address")
        @Size(max = 254, message = "Participant email is too long")
        String email) {
}
