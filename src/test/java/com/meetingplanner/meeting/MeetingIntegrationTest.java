package com.meetingplanner.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.meetingplanner.IntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String ownerToken;

    @BeforeEach
    void setUpOwner() throws Exception {
        ownerToken = tokenFor("Olivia", "olivia@example.com");
    }

    @Test
    void listingDoesNotQueryParticipantsOncePerMeeting() throws Exception {
        createMeetingsWithParticipants(2);
        long statementsForTwo = statementsToListMeetings();

        createMeetingsWithParticipants(10);
        long statementsForTwelve = statementsToListMeetings();

        assertThat(statementsForTwelve).isEqualTo(statementsForTwo);
    }

    private void createMeetingsWithParticipants(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(meetingBody("Meeting", START, END, List.of(
                                    Map.of("name", "Bob", "email", "bob@example.com"),
                                    Map.of("name", "Carol", "email", "carol@example.com"))))))
                    .andExpect(status().isCreated());
        }
    }

    private long statementsToListMeetings() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mockMvc.perform(withToken(get("/api/meetings").param("size", "50"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].participantCount").value(2));
        return statistics.getPrepareStatementCount();
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
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.organizer.email").value("olivia@example.com"))
                .andExpect(jsonPath("$.participants", hasSize(1)))
                .andExpect(jsonPath("$.participants[0].email").value("bob@example.com"));

        assertThat(meetingRepository.count()).isEqualTo(1);
    }

    @Test
    void endedMeetingIsLockedButCanStillBeDeleted() throws Exception {
        // Ended two weeks before TEST_NOW.
        String created = mockMvc.perform(withToken(post("/api/meetings"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Retro", "2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z",
                                List.of(Map.of("name", "Bob", "email", "bob@example.com"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completed").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode meeting = objectMapper.readTree(created);
        long meetingId = meeting.get("id").asLong();
        long bobId = meeting.get("participants").get(0).get("id").asLong();

        mockMvc.perform(withToken(get("/api/meetings"), ownerToken))
                .andExpect(jsonPath("$.content[0].completed").value(true));

        mockMvc.perform(withToken(put("/api/meetings/" + meetingId), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Rewritten", "startTime", START, "endTime", END))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(MeetingService.MEETING_ENDED_MESSAGE));
        mockMvc.perform(withToken(post("/api/meetings/" + meetingId + "/participants"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Eve", "email", "eve@example.com"))))
                .andExpect(status().isConflict());
        mockMvc.perform(withToken(delete("/api/meetings/" + meetingId + "/participants/" + bobId), ownerToken))
                .andExpect(status().isConflict());

        mockMvc.perform(withToken(get("/api/meetings/" + meetingId), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Retro"))
                .andExpect(jsonPath("$.participants", hasSize(1)));

        mockMvc.perform(withToken(delete("/api/meetings/" + meetingId), ownerToken))
                .andExpect(status().isNoContent());
        assertThat(meetingRepository.count()).isZero();
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
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));

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
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].owner").value(false))
                .andExpect(jsonPath("$.content[0].organizerName").value("Olivia"));
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
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].title").value("Sooner"))
                .andExpect(jsonPath("$.content[1].title").value("Later"));
    }

    @Test
    void listIsPagedWithTotals() throws Exception {
        for (int day = 1; day <= 12; day++) {
            String date = "2026-11-%02d".formatted(day);
            createMeeting(ownerToken, "Meeting " + day, date + "T09:00:00Z", date + "T10:00:00Z");
        }

        mockMvc.perform(withToken(get("/api/meetings").param("size", "5"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.content[0].title").value("Meeting 1"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(3));
        mockMvc.perform(withToken(get("/api/meetings").param("size", "5").param("page", "2"), ownerToken))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].title").value("Meeting 11"))
                .andExpect(jsonPath("$.content[1].title").value("Meeting 12"));
        mockMvc.perform(withToken(get("/api/meetings").param("size", "5").param("page", "9"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(12));
    }

    @Test
    void searchMatchesTitleOrOrganizerNameCaseInsensitively() throws Exception {
        createMeeting(ownerToken, "Sprint planning");
        createMeeting(ownerToken, "Design review");
        createMeeting(ownerToken, "SPRINT retro");
        createMeeting(ownerToken, "Budget 50% cut");
        createMeeting(ownerToken, "Budget 500 plan");
        String malloryToken = tokenFor("Mallory Jones", "mallory@example.com");
        mockMvc.perform(withToken(post("/api/meetings"), malloryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Vendor call", START, END,
                                List.of(Map.of("name", "Olivia", "email", "olivia@example.com"))))))
                .andExpect(status().isCreated());

        mockMvc.perform(withToken(get("/api/meetings").param("q", "  sprint "), ownerToken))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.counts.all").value(2));
        mockMvc.perform(withToken(get("/api/meetings").param("q", "mallory"), ownerToken))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Vendor call"));
        // LIKE wildcards in the search text are matched literally.
        mockMvc.perform(withToken(get("/api/meetings").param("q", "50%"), ownerToken))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Budget 50% cut"));
        // Search never reaches meetings the user can't see.
        mockMvc.perform(withToken(get("/api/meetings").param("q", "sprint"), malloryToken))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void filtersNarrowTheListAndCountsDescribeEveryTab() throws Exception {
        createMeeting(ownerToken, "Next week", "2026-10-22T09:00:00Z", "2026-10-22T10:00:00Z");
        createMeeting(ownerToken, "Tomorrow", "2026-10-16T09:00:00Z", "2026-10-16T10:00:00Z");
        createMeeting(ownerToken, "Last month", "2026-09-10T09:00:00Z", "2026-09-10T10:00:00Z");
        createMeeting(ownerToken, "Yesterday", "2026-10-14T09:00:00Z", "2026-10-14T10:00:00Z");
        String malloryToken = tokenFor("Mallory", "mallory@example.com");
        mockMvc.perform(withToken(post("/api/meetings"), malloryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Invite", "2026-10-20T09:00:00Z", "2026-10-20T10:00:00Z",
                                List.of(Map.of("name", "Olivia", "email", "olivia@example.com"))))))
                .andExpect(status().isCreated());

        // "All": upcoming soonest first, then completed most recent first.
        mockMvc.perform(withToken(get("/api/meetings"), ownerToken))
                .andExpect(jsonPath("$.content[*].title")
                        .value(contains("Tomorrow", "Invite", "Next week", "Yesterday", "Last month")))
                .andExpect(jsonPath("$.counts.all").value(5))
                .andExpect(jsonPath("$.counts.upcoming").value(3))
                .andExpect(jsonPath("$.counts.completed").value(2))
                .andExpect(jsonPath("$.counts.organized").value(4))
                .andExpect(jsonPath("$.counts.invited").value(1));

        mockMvc.perform(withToken(get("/api/meetings").param("filter", "upcoming"), ownerToken))
                .andExpect(jsonPath("$.content[*].title").value(contains("Tomorrow", "Invite", "Next week")));
        mockMvc.perform(withToken(get("/api/meetings").param("filter", "COMPLETED"), ownerToken))
                .andExpect(jsonPath("$.content[*].title").value(contains("Yesterday", "Last month")));
        mockMvc.perform(withToken(get("/api/meetings").param("filter", "invited"), ownerToken))
                .andExpect(jsonPath("$.content[*].title").value(contains("Invite")));
        mockMvc.perform(withToken(get("/api/meetings").param("filter", "organized"), ownerToken))
                .andExpect(jsonPath("$.totalElements").value(4))
                // The tab counts stay the same whichever tab is selected.
                .andExpect(jsonPath("$.counts.all").value(5));
    }

    @Test
    void invalidListParametersAreRejected() throws Exception {
        mockMvc.perform(withToken(get("/api/meetings").param("size", "0"), ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size must be between 1 and 50"));
        mockMvc.perform(withToken(get("/api/meetings").param("size", "51"), ownerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withToken(get("/api/meetings").param("page", "-1"), ownerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withToken(get("/api/meetings").param("page", "two"), ownerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withToken(get("/api/meetings").param("filter", "mine"), ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("filter must be one of: all, upcoming, completed, organized, invited"));
        mockMvc.perform(withToken(get("/api/meetings").param("q", "x".repeat(101)), ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statsSummarizeVisibleMeetingsByMonthInTheRequestedZone() throws Exception {
        createMeeting(ownerToken, "Upcoming", "2026-10-20T09:00:00Z", "2026-10-20T10:00:00Z");
        createMeeting(ownerToken, "Earlier this month", "2026-10-05T09:00:00Z", "2026-10-05T10:00:00Z");
        // 31 Aug 20:00 UTC is already 1 Sep in Kolkata (UTC+5:30).
        createMeeting(ownerToken, "Late August UTC", "2026-08-31T20:00:00Z", "2026-08-31T21:00:00Z");
        createMeeting(ownerToken, "Long ago", "2025-01-10T09:00:00Z", "2025-01-10T10:00:00Z");
        String malloryToken = tokenFor("Mallory", "mallory@example.com");
        mockMvc.perform(withToken(post("/api/meetings"), malloryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(meetingBody("Invite", "2026-09-25T09:00:00Z", "2026-09-25T10:00:00Z",
                                List.of(Map.of("name", "Olivia", "email", "olivia@example.com"),
                                        Map.of("name", "Sam", "email", "sam@example.com"))))))
                .andExpect(status().isCreated());
        createMeeting(malloryToken, "Not visible", "2026-10-06T09:00:00Z", "2026-10-06T10:00:00Z");

        mockMvc.perform(withToken(get("/api/meetings/stats").param("zone", "Asia/Kolkata").param("months", "3"),
                        ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.upcoming").value(1))
                .andExpect(jsonPath("$.completed").value(4))
                .andExpect(jsonPath("$.organized").value(4))
                .andExpect(jsonPath("$.invited").value(1))
                .andExpect(jsonPath("$.last30Days").value(2))
                .andExpect(jsonPath("$.previous30Days").value(1))
                .andExpect(jsonPath("$.months[*].month").value(contains("2026-08", "2026-09", "2026-10")))
                .andExpect(jsonPath("$.months[0].organized").value(0))
                .andExpect(jsonPath("$.months[1].organized").value(1))
                .andExpect(jsonPath("$.months[1].invited").value(1))
                .andExpect(jsonPath("$.months[1].participants").value(2))
                .andExpect(jsonPath("$.months[2].organized").value(2));

        mockMvc.perform(withToken(get("/api/meetings/stats").param("zone", "Mars/Olympus"), ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown time zone: Mars/Olympus"));
        mockMvc.perform(withToken(get("/api/meetings/stats").param("months", "25"), ownerToken))
                .andExpect(status().isBadRequest());
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
