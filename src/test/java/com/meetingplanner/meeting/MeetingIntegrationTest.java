package com.meetingplanner.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.meetingplanner.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeetingIntegrationTest extends IntegrationTestSupport {

    private static final String START = "2026-11-01T09:00:00Z";
    private static final String END = "2026-11-01T10:00:00Z";

    private String ownerToken;

    @BeforeEach
    void setUpOwner() throws Exception {
        ownerToken = tokenFor("Olivia", "olivia@example.com");
    }

    @Test
    void createMeetingWithParticipants() throws Exception {
        mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Sprint planning", START, END,
                                List.of(Map.of("name", "Bob", "email", "Bob@Example.com"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Sprint planning"))
                .andExpect(jsonPath("$.owner").value(true))
                .andExpect(jsonPath("$.organizer.email").value("olivia@example.com"))
                .andExpect(jsonPath("$.participants", hasSize(1)))
                .andExpect(jsonPath("$.participants[0].email").value("bob@example.com"));

        assertThat(meetingRepository.count()).isEqualTo(1);
    }

    @Test
    void clientSuppliedOwnerFieldsAreIgnored() throws Exception {
        String otherToken = tokenFor("Mallory", "mallory@example.com");
        Map<String, Object> body = meetingBody("Spoof", START, END, List.of());
        body.put("createdBy", 9999);
        body.put("organizer", Map.of("id", 9999));

        mockMvc.perform(withToken(post("/api/meetings"), otherToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organizer.email").value("mallory@example.com"));
    }

    @Test
    void blankTitleIsRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("   ", START, END, List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value("Title is required"));
    }

    @Test
    void endTimeBeforeStartIsRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Bad", END, START, List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endTime").value("End time must be after start time"));
    }

    @Test
    void endTimeEqualToStartIsRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Zero length", START, START, List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.endTime").exists());
    }

    @Test
    void missingTimesAndMalformedJsonAreRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.startTime").exists())
                .andExpect(jsonPath("$.fieldErrors.endTime").exists());

        mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));
    }

    @Test
    void invalidParticipantEmailInCreateIsRejectedAndNothingIsSaved() throws Exception {
        mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Team", START, END,
                                List.of(Map.of("name", "Bob", "email", "not-an-email"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['participants[0].email']").exists());

        assertThat(meetingRepository.count()).isZero();
    }

    @Test
    void addAndRemoveParticipant() throws Exception {
        long meetingId = createMeeting(ownerToken, "Team sync");

        String body = mockMvc.perform(withToken(post("/api/meetings/" + meetingId + "/participants"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Carol", "email", "carol@example.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Carol"))
                .andReturn().getResponse().getContentAsString();
        long participantId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(withToken(get("/api/meetings/" + meetingId), ownerToken))
                .andExpect(jsonPath("$.participants", hasSize(1)));

        mockMvc.perform(withToken(delete("/api/meetings/" + meetingId + "/participants/" + participantId), ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(withToken(get("/api/meetings/" + meetingId), ownerToken))
                .andExpect(jsonPath("$.participants", hasSize(0)));
    }

    @Test
    void invalidParticipantEmailIsRejected() throws Exception {
        long meetingId = createMeeting(ownerToken, "Team sync");

        mockMvc.perform(withToken(post("/api/meetings/" + meetingId + "/participants"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Carol", "email", "carol-at-example"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value("Participant email must be a valid email address"));
    }

    @Test
    void duplicateParticipantIsConflict() throws Exception {
        long meetingId = createMeeting(ownerToken, "Team sync");
        String path = "/api/meetings/" + meetingId + "/participants";
        mockMvc.perform(withToken(post(path), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Carol", "email", "carol@example.com"))))
                .andExpect(status().isCreated());

        mockMvc.perform(withToken(post(path), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Carol 2", "email", "CAROL@example.com"))))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerCanUpdateAndDeleteMeeting() throws Exception {
        long meetingId = createMeeting(ownerToken, "Original");

        mockMvc.perform(withToken(put("/api/meetings/" + meetingId), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Updated", "description", "New agenda",
                                "startTime", START, "endTime", "2026-11-01T11:00:00Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated"))
                .andExpect(jsonPath("$.endTime").value("2026-11-01T11:00:00Z"));

        mockMvc.perform(withToken(put("/api/meetings/" + meetingId), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Updated", "startTime", END, "endTime", START))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(withToken(delete("/api/meetings/" + meetingId), ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(withToken(get("/api/meetings/" + meetingId), ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersCannotSeeOrModifyMeeting() throws Exception {
        long meetingId = createMeeting(ownerToken, "Private");
        String strangerToken = tokenFor("Mallory", "mallory@example.com");

        mockMvc.perform(withToken(get("/api/meetings/" + meetingId), strangerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(put("/api/meetings/" + meetingId), strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Hacked", "startTime", START, "endTime", END))))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete("/api/meetings/" + meetingId), strangerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/meetings/" + meetingId + "/participants"), strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Eve", "email", "eve@example.com"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/meetings"), strangerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // The meeting is untouched.
        mockMvc.perform(withToken(get("/api/meetings/" + meetingId), ownerToken))
                .andExpect(jsonPath("$.title").value("Private"));
    }

    @Test
    void invitedUserCanViewButNotModify() throws Exception {
        String inviteeToken = tokenFor("Pat", "pat@example.com");
        String body = mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Review", START, END,
                                List.of(Map.of("name", "Pat", "email", "PAT@example.com"))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode meeting = objectMapper.readTree(body);
        long meetingId = meeting.get("id").asLong();
        long participantId = meeting.get("participants").get(0).get("id").asLong();

        mockMvc.perform(withToken(get("/api/meetings"), inviteeToken))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].owner").value(false))
                .andExpect(jsonPath("$[0].organizerName").value("Olivia"));
        mockMvc.perform(withToken(get("/api/meetings/" + meetingId), inviteeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(false));

        mockMvc.perform(withToken(put("/api/meetings/" + meetingId), inviteeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Changed", "startTime", START, "endTime", END))))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(delete("/api/meetings/" + meetingId + "/participants/" + participantId), inviteeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(delete("/api/meetings/" + meetingId), inviteeToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOnlyAccessibleMeetingsSortedByStart() throws Exception {
        createMeeting(ownerToken, "Later", "2026-12-01T09:00:00Z", "2026-12-01T10:00:00Z");
        createMeeting(ownerToken, "Sooner", START, END);
        createMeeting(tokenFor("Mallory", "mallory@example.com"), "Not mine");

        mockMvc.perform(withToken(get("/api/meetings"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("Sooner"))
                .andExpect(jsonPath("$[1].title").value("Later"));
    }

    private long createMeeting(String token, String title) throws Exception {
        return createMeeting(token, title, START, END);
    }

    private long createMeeting(String token, String title, String start, String end) throws Exception {
        String body = mockMvc.perform(withToken(post("/api/meetings"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody(title, start, end, List.of()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private static Map<String, Object> meetingBody(String title, String start, String end,
                                                   List<Map<String, String>> participants) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "Agenda");
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("participants", participants);
        return body;
    }
}
