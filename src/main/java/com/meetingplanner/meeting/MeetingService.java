package com.meetingplanner.meeting;

import com.meetingplanner.common.ApiException;
import com.meetingplanner.common.Emails;
import com.meetingplanner.meeting.dto.CreateMeetingRequest;
import com.meetingplanner.meeting.dto.MeetingPageResponse;
import com.meetingplanner.meeting.dto.MeetingResponse;
import com.meetingplanner.meeting.dto.MeetingStatsResponse;
import com.meetingplanner.meeting.dto.MeetingSummaryResponse;
import com.meetingplanner.meeting.dto.ParticipantRequest;
import com.meetingplanner.meeting.dto.ParticipantResponse;
import com.meetingplanner.meeting.dto.UpdateMeetingRequest;
import com.meetingplanner.security.AuthUser;
import com.meetingplanner.user.User;
import com.meetingplanner.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.meetingplanner.meeting.MeetingSpecifications.accessibleTo;
import static com.meetingplanner.meeting.MeetingSpecifications.any;
import static com.meetingplanner.meeting.MeetingSpecifications.endedAt;
import static com.meetingplanner.meeting.MeetingSpecifications.matching;
import static com.meetingplanner.meeting.MeetingSpecifications.notEndedAt;
import static com.meetingplanner.meeting.MeetingSpecifications.notOwnedBy;
import static com.meetingplanner.meeting.MeetingSpecifications.ownedBy;
import static com.meetingplanner.meeting.MeetingSpecifications.startsBetween;
import static com.meetingplanner.meeting.MeetingSpecifications.upcomingFirst;

/**
 * Meeting business rules and authorization.
 * <ul>
 *   <li>The organizer (creator) can view, edit and delete the meeting and manage its participants.</li>
 *   <li>A user whose account e-mail is on the participant list can view the meeting read-only.</li>
 *   <li>Everyone else gets 404, so the existence of other users' meetings is not revealed.</li>
 *   <li>Once a meeting has ended it is complete: its details and participant list can no longer be changed
 *       (409). The organizer can still delete it.</li>
 * </ul>
 */
@Service
public class MeetingService {

    static final String MEETING_ENDED_MESSAGE = "This meeting has already ended, so it can no longer be changed";
    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_STATS_MONTHS = 24;

    private final MeetingRepository meetingRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public MeetingService(MeetingRepository meetingRepository, UserRepository userRepository, Clock clock) {
        this.meetingRepository = meetingRepository;
        this.userRepository = userRepository;
        this.clock = clock;
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
        return MeetingResponse.from(meetingRepository.save(meeting), currentUser.id(), clock.instant());
    }

    /**
     * One page of the meetings the user can see, optionally narrowed by a search term (title or organizer
     * name) and a filter tab. Upcoming meetings are listed soonest first, completed ones most recent first;
     * the mixed tabs show upcoming before completed.
     */
    @Transactional(readOnly = true)
    public MeetingPageResponse search(AuthUser currentUser, String query, String filterParam, int page, int size) {
        if (page < 0) {
            throw ApiException.badRequest("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String term = query == null ? "" : query.trim();
        if (term.length() > MAX_QUERY_LENGTH) {
            throw ApiException.badRequest("Search text must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        MeetingFilter filter = MeetingFilter.fromParam(filterParam);
        Instant now = clock.instant();
        Long userId = currentUser.id();

        Specification<Meeting> visible = accessibleTo(userId, currentUser.email()).and(matching(term));
        Specification<Meeting> filtered = visible.and(filterSpec(filter, userId, now));
        Page<Meeting> result = switch (filter) {
            case UPCOMING -> meetingRepository.findAll(filtered,
                    PageRequest.of(page, size, Sort.by("startTime", "id").ascending()));
            case COMPLETED -> meetingRepository.findAll(filtered,
                    PageRequest.of(page, size, Sort.by("startTime").descending().and(Sort.by("id"))));
            default -> meetingRepository.findAll(filtered.and(upcomingFirst(now)), PageRequest.of(page, size));
        };

        return new MeetingPageResponse(
                result.getContent().stream().map(m -> MeetingSummaryResponse.from(m, userId, now)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                countByTab(visible, userId, now));
    }

    /**
     * Dashboard figures: totals per status, meetings started in the last 30 days (and the 30 before), and
     * meetings per calendar month for the last {@code months} months in the user's time zone.
     */
    @Transactional(readOnly = true)
    public MeetingStatsResponse stats(AuthUser currentUser, String zoneParam, int months) {
        if (months < 1 || months > MAX_STATS_MONTHS) {
            throw ApiException.badRequest("months must be between 1 and " + MAX_STATS_MONTHS);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneParam);
        } catch (DateTimeException ex) {
            throw ApiException.badRequest("Unknown time zone: " + zoneParam);
        }
        Instant now = clock.instant();
        Long userId = currentUser.id();
        Specification<Meeting> visible = accessibleTo(userId, currentUser.email());
        Instant thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);
        MeetingPageResponse.Counts counts = countByTab(visible, userId, now);

        return new MeetingStatsResponse(
                counts.all(), counts.upcoming(), counts.completed(), counts.organized(), counts.invited(),
                meetingRepository.count(visible.and(startsBetween(thirtyDaysAgo, now))),
                meetingRepository.count(visible.and(startsBetween(now.minus(60, ChronoUnit.DAYS), thirtyDaysAgo))),
                countByMonth(visible, userId, YearMonth.from(now.atZone(zone)), months, zone));
    }

    /** Number of meetings in each filter tab; shared by the list's tab badges and the dashboard totals. */
    private MeetingPageResponse.Counts countByTab(Specification<Meeting> visible, Long userId, Instant now) {
        return new MeetingPageResponse.Counts(
                meetingRepository.count(visible),
                meetingRepository.count(visible.and(notEndedAt(now))),
                meetingRepository.count(visible.and(endedAt(now))),
                meetingRepository.count(visible.and(ownedBy(userId))),
                meetingRepository.count(visible.and(notOwnedBy(userId))));
    }

    /** Meetings starting in each of the {@code months} calendar months ending with {@code current}, oldest first. */
    private List<MeetingStatsResponse.MonthCount> countByMonth(
            Specification<Meeting> visible, Long userId, YearMonth current, int months, ZoneId zone) {
        YearMonth first = current.minusMonths(months - 1L);
        Map<YearMonth, MonthTally> tallies = new LinkedHashMap<>();
        for (YearMonth month = first; !month.isAfter(current); month = month.plusMonths(1)) {
            tallies.put(month, new MonthTally());
        }
        Instant from = first.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = current.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        for (Meeting meeting : meetingRepository.findAll(visible.and(startsBetween(from, to)))) {
            MonthTally tally = tallies.get(YearMonth.from(meeting.getStartTime().atZone(zone)));
            if (meeting.isOwnedBy(userId)) {
                tally.organized++;
            } else {
                tally.invited++;
            }
            tally.participants += meeting.getParticipants().size();
        }
        return tallies.entrySet().stream()
                .map(e -> new MeetingStatsResponse.MonthCount(
                        e.getKey().toString(), e.getValue().organized, e.getValue().invited, e.getValue().participants))
                .toList();
    }

    private static final class MonthTally {
        private long organized;
        private long invited;
        private long participants;
    }

    private static Specification<Meeting> filterSpec(MeetingFilter filter, Long userId, Instant now) {
        return switch (filter) {
            case ALL -> any();
            case UPCOMING -> notEndedAt(now);
            case COMPLETED -> endedAt(now);
            case ORGANIZED -> ownedBy(userId);
            case INVITED -> notOwnedBy(userId);
        };
    }

    @Transactional(readOnly = true)
    public MeetingResponse get(AuthUser currentUser, Long meetingId) {
        return MeetingResponse.from(findViewable(currentUser, meetingId), currentUser.id(), clock.instant());
    }

    @Transactional
    public MeetingResponse update(AuthUser currentUser, Long meetingId, UpdateMeetingRequest request) {
        Meeting meeting = findChangeable(currentUser, meetingId);
        meeting.update(request.title().trim(), normalizeDescription(request.description()),
                request.startTime(), request.endTime());
        return MeetingResponse.from(meetingRepository.saveAndFlush(meeting), currentUser.id(), clock.instant());
    }

    @Transactional
    public void delete(AuthUser currentUser, Long meetingId) {
        meetingRepository.delete(findOwned(currentUser, meetingId));
    }

    @Transactional
    public ParticipantResponse addParticipant(AuthUser currentUser, Long meetingId, ParticipantRequest request) {
        Meeting meeting = findChangeable(currentUser, meetingId);
        String email = Emails.normalize(request.email());
        if (meeting.hasParticipantEmail(email)) {
            throw ApiException.conflict("This person is already a participant");
        }
        if (meeting.getParticipants().size() >= Meeting.MAX_PARTICIPANTS) {
            throw ApiException.badRequest("A meeting can have at most " + Meeting.MAX_PARTICIPANTS + " participants");
        }
        Participant participant = meeting.addParticipant(request.name().trim(), email);
        // Flush (not merge) so this exact instance is persisted via cascade and receives its generated id.
        meetingRepository.flush();
        return ParticipantResponse.from(participant);
    }

    @Transactional
    public void removeParticipant(AuthUser currentUser, Long meetingId, Long participantId) {
        Meeting meeting = findChangeable(currentUser, meetingId);
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

    /** Owned by the current user and not yet ended: the only state in which details or participants may change. */
    private Meeting findChangeable(AuthUser currentUser, Long meetingId) {
        Meeting meeting = findOwned(currentUser, meetingId);
        if (meeting.hasEnded(clock.instant())) {
            throw ApiException.conflict(MEETING_ENDED_MESSAGE);
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
