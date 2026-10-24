package com.meetingplanner.meeting;

import com.meetingplanner.common.ApiException;
import com.meetingplanner.common.Emails;
import com.meetingplanner.meeting.dto.CreateMeetingRequest;
import com.meetingplanner.meeting.dto.MeetingResponse;
import com.meetingplanner.meeting.dto.MeetingSummaryResponse;
import com.meetingplanner.meeting.dto.ParticipantRequest;
import com.meetingplanner.meeting.dto.ParticipantResponse;
import com.meetingplanner.meeting.dto.UpdateMeetingRequest;
import com.meetingplanner.security.AuthUser;
import com.meetingplanner.user.User;
import com.meetingplanner.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Meeting business rules and authorization.
 * <ul>
 *   <li>The organizer (creator) can view, edit and delete the meeting and manage its participants.</li>
 *   <li>A user whose account e-mail is on the participant list can view the meeting read-only.</li>
 *   <li>Everyone else gets 404, so the existence of other users' meetings is not revealed.</li>
 * </ul>
 */
@Service
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final UserRepository userRepository;

    public MeetingService(MeetingRepository meetingRepository, UserRepository userRepository) {
        this.meetingRepository = meetingRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public MeetingResponse create(AuthUser currentUser, CreateMeetingRequest request) {
        User owner = userRepository.findById(currentUser.id())
                .orElseThrow(() -> ApiException.unauthorized("Authentication required"));
        Meeting meeting = new Meeting(request.title().trim(), normalizeDescription(request.description()),
                request.startTime(), request.endTime(), owner);

        Set<String> seen = new HashSet<>();
        for (ParticipantRequest p : request.participantsOrEmpty()) {
            String email = Emails.normalize(p.email());
            if (!seen.add(email)) {
                throw ApiException.badRequest("Duplicate participant email: " + email);
            }
            meeting.addParticipant(p.name().trim(), email);
        }
        // Meeting and participants are persisted atomically via cascade inside this transaction.
        return MeetingResponse.from(meetingRepository.save(meeting), currentUser.id());
    }

    @Transactional(readOnly = true)
    public List<MeetingSummaryResponse> listAccessible(AuthUser currentUser) {
        return meetingRepository.findAccessibleBy(currentUser.id(), currentUser.email()).stream()
                .map(m -> MeetingSummaryResponse.from(m, currentUser.id()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MeetingResponse get(AuthUser currentUser, Long meetingId) {
        return MeetingResponse.from(findViewable(currentUser, meetingId), currentUser.id());
    }

    @Transactional
    public MeetingResponse update(AuthUser currentUser, Long meetingId, UpdateMeetingRequest request) {
        Meeting meeting = findOwned(currentUser, meetingId);
        meeting.update(request.title().trim(), normalizeDescription(request.description()),
                request.startTime(), request.endTime());
        return MeetingResponse.from(meetingRepository.saveAndFlush(meeting), currentUser.id());
    }

    @Transactional
    public void delete(AuthUser currentUser, Long meetingId) {
        meetingRepository.delete(findOwned(currentUser, meetingId));
    }

    @Transactional
    public ParticipantResponse addParticipant(AuthUser currentUser, Long meetingId, ParticipantRequest request) {
        Meeting meeting = findOwned(currentUser, meetingId);
        String email = Emails.normalize(request.email());
        if (meeting.hasParticipantEmail(email)) {
            throw ApiException.conflict("This person is already a participant");
        }
        Participant participant = meeting.addParticipant(request.name().trim(), email);
        // Flush (not merge) so this exact instance is persisted via cascade and receives its generated id.
        meetingRepository.flush();
        return ParticipantResponse.from(participant);
    }

    @Transactional
    public void removeParticipant(AuthUser currentUser, Long meetingId, Long participantId) {
        Meeting meeting = findOwned(currentUser, meetingId);
        boolean removed = meeting.getParticipants().removeIf(p -> p.getId().equals(participantId));
        if (!removed) {
            throw ApiException.notFound("Participant not found");
        }
    }

    private Meeting findViewable(AuthUser currentUser, Long meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> ApiException.notFound("Meeting not found"));
        if (!meeting.isOwnedBy(currentUser.id()) && !meeting.hasParticipantEmail(currentUser.email())) {
            throw ApiException.notFound("Meeting not found");
        }
        return meeting;
    }

    private Meeting findOwned(AuthUser currentUser, Long meetingId) {
        Meeting meeting = findViewable(currentUser, meetingId);
        if (!meeting.isOwnedBy(currentUser.id())) {
            throw ApiException.forbidden("Only the organizer can modify this meeting");
        }
        return meeting;
    }

    private static String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }
}
