package com.meetingplanner.meeting;

import com.meetingplanner.common.ApiException;
import com.meetingplanner.meeting.dto.CreateMeetingRequest;
import com.meetingplanner.meeting.dto.MeetingResponse;
import com.meetingplanner.meeting.dto.ParticipantRequest;
import com.meetingplanner.meeting.dto.UpdateMeetingRequest;
import com.meetingplanner.security.AuthUser;
import com.meetingplanner.user.User;
import com.meetingplanner.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    private static final Instant START = Instant.parse("2026-11-01T09:00:00Z");
    private static final Instant END = Instant.parse("2026-11-01T10:00:00Z");
    /** "Now" for every test: before START, so meeting(...) is upcoming unless a test says otherwise. */
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private UserRepository userRepository;

    private MeetingService service;
    private User owner;
    private AuthUser ownerAuth;
    private AuthUser strangerAuth;

    @BeforeEach
    void setUp() {
        service = new MeetingService(meetingRepository, userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        owner = withId(new User("Olivia", "olivia@example.com", "hash"), 1L);
        ownerAuth = new AuthUser(1L, "olivia@example.com");
        strangerAuth = new AuthUser(2L, "stranger@example.com");
    }

    @Test
    void createPersistsMeetingWithNormalizedParticipantsForCurrentUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        MeetingResponse response = service.create(ownerAuth, new CreateMeetingRequest(
                "  Planning  ", "  ", START, END,
                List.of(new ParticipantRequest("Bob", " Bob@Example.com "))));

        assertThat(response.title()).isEqualTo("Planning");
        assertThat(response.description()).isNull();
        assertThat(response.owner()).isTrue();
        assertThat(response.organizer().id()).isEqualTo(1L);
        assertThat(response.participants()).singleElement()
                .satisfies(p -> assertThat(p.email()).isEqualTo("bob@example.com"));
    }

    @Test
    void createRejectsDuplicateParticipantEmailsInSameRequest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.create(ownerAuth, new CreateMeetingRequest("Planning", null, START, END,
                List.of(new ParticipantRequest("Bob", "bob@example.com"),
                        new ParticipantRequest("Bobby", "BOB@example.com")))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void strangerCannotViewMeetingAndGetsNotFound() {
        Meeting meeting = meeting(10L);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));

        assertStatus(() -> service.get(strangerAuth, 10L), HttpStatus.NOT_FOUND);
    }

    @Test
    void invitedParticipantCanViewButNotModify() {
        Meeting meeting = meeting(10L);
        meeting.addParticipant("Pat", "pat@example.com");
        AuthUser invited = new AuthUser(3L, "pat@example.com");
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));

        MeetingResponse view = service.get(invited, 10L);
        assertThat(view.owner()).isFalse();

        assertStatus(() -> service.update(invited, 10L, new UpdateMeetingRequest("Hijack", null, START, END)),
                HttpStatus.FORBIDDEN);
        assertStatus(() -> service.delete(invited, 10L), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.addParticipant(invited, 10L, new ParticipantRequest("Eve", "eve@example.com")),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void ownerCanUpdateMeeting() {
        Meeting meeting = meeting(10L);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));
        when(meetingRepository.saveAndFlush(meeting)).thenReturn(meeting);

        MeetingResponse response = service.update(ownerAuth, 10L,
                new UpdateMeetingRequest("Renamed", "Agenda", START, END.plusSeconds(1800)));

        assertThat(response.title()).isEqualTo("Renamed");
        assertThat(response.description()).isEqualTo("Agenda");
        assertThat(response.endTime()).isEqualTo(END.plusSeconds(1800));
    }

    @Test
    void addingExistingParticipantEmailIsConflict() {
        Meeting meeting = meeting(10L);
        meeting.addParticipant("Bob", "bob@example.com");
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));

        assertStatus(() -> service.addParticipant(ownerAuth, 10L, new ParticipantRequest("Bob", "BOB@example.com")),
                HttpStatus.CONFLICT);
    }

    @Test
    void removingUnknownParticipantIsNotFound() {
        Meeting meeting = meeting(10L);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));

        assertStatus(() -> service.removeParticipant(ownerAuth, 10L, 999L), HttpStatus.NOT_FOUND);
    }

    @Test
    void endedMeetingIsReportedCompletedAndCannotBeChanged() {
        Meeting meeting = endedMeeting(10L);
        meeting.addParticipant("Bob", "bob@example.com");
        Long bobId = 5L;
        ReflectionTestUtils.setField(meeting.getParticipants().get(0), "id", bobId);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));

        assertThat(service.get(ownerAuth, 10L).completed()).isTrue();
        assertStatus(() -> service.update(ownerAuth, 10L, new UpdateMeetingRequest("Renamed", null, START, END)),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.addParticipant(ownerAuth, 10L, new ParticipantRequest("Eve", "eve@example.com")),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.removeParticipant(ownerAuth, 10L, bobId), HttpStatus.CONFLICT);

        assertThat(meeting.getTitle()).isEqualTo("Planning");
        assertThat(meeting.getParticipants()).hasSize(1);
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void meetingIsCompleteFromItsExactEndTime() {
        Meeting meeting = new Meeting("Standup", null, NOW.minusSeconds(3600), NOW, owner);
        ReflectionTestUtils.setField(meeting, "id", 11L);
        when(meetingRepository.findById(11L)).thenReturn(Optional.of(meeting));

        assertThat(service.get(ownerAuth, 11L).completed()).isTrue();
        assertStatus(() -> service.addParticipant(ownerAuth, 11L, new ParticipantRequest("Eve", "eve@example.com")),
                HttpStatus.CONFLICT);
    }

    @Test
    void ongoingMeetingCanStillBeChanged() {
        Meeting meeting = new Meeting("Workshop", null, NOW.minusSeconds(1800), NOW.plusSeconds(1800), owner);
        ReflectionTestUtils.setField(meeting, "id", 12L);
        when(meetingRepository.findById(12L)).thenReturn(Optional.of(meeting));

        assertThat(service.get(ownerAuth, 12L).completed()).isFalse();
        assertThat(service.addParticipant(ownerAuth, 12L, new ParticipantRequest("Eve", "eve@example.com")).email())
                .isEqualTo("eve@example.com");
    }

    @Test
    void organizerCanStillDeleteAnEndedMeeting() {
        Meeting meeting = endedMeeting(10L);
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));

        service.delete(ownerAuth, 10L);

        verify(meetingRepository).delete(meeting);
    }

    @Test
    void inviteeOfEndedMeetingStillGetsForbiddenRatherThanConflict() {
        Meeting meeting = endedMeeting(10L);
        meeting.addParticipant("Pat", "pat@example.com");
        when(meetingRepository.findById(10L)).thenReturn(Optional.of(meeting));

        assertStatus(() -> service.update(new AuthUser(3L, "pat@example.com"), 10L,
                new UpdateMeetingRequest("Hijack", null, START, END)), HttpStatus.FORBIDDEN);
    }

    @Test
    void missingMeetingIsNotFound() {
        when(meetingRepository.findById(99L)).thenReturn(Optional.empty());

        assertStatus(() -> service.get(ownerAuth, 99L), HttpStatus.NOT_FOUND);
    }

    private Meeting meeting(Long id) {
        Meeting meeting = new Meeting("Planning", null, START, END, owner);
        ReflectionTestUtils.setField(meeting, "id", id);
        return meeting;
    }

    private Meeting endedMeeting(Long id) {
        Meeting meeting = new Meeting("Planning", null, NOW.minusSeconds(7200), NOW.minusSeconds(3600), owner);
        ReflectionTestUtils.setField(meeting, "id", id);
        return meeting;
    }

    private static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(expected));
    }

    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
