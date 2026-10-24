package com.meetingplanner.meeting;

import com.meetingplanner.meeting.dto.CreateMeetingRequest;
import com.meetingplanner.meeting.dto.MeetingPageResponse;
import com.meetingplanner.meeting.dto.MeetingResponse;
import com.meetingplanner.meeting.dto.MeetingStatsResponse;
import com.meetingplanner.meeting.dto.ParticipantRequest;
import com.meetingplanner.meeting.dto.ParticipantResponse;
import com.meetingplanner.meeting.dto.UpdateMeetingRequest;
import com.meetingplanner.security.AuthUser;
import com.meetingplanner.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private final MeetingService meetingService;

    public MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingResponse create(@CurrentUser AuthUser user, @Valid @RequestBody CreateMeetingRequest request) {
        return meetingService.create(user, request);
    }

    /**
     * Paged meeting list. {@code q} searches titles and organizer names; {@code filter} is one of all,
     * upcoming, completed, organized, invited; {@code page} is zero-based and {@code size} is 1–50.
     */
    @GetMapping
    public MeetingPageResponse list(@CurrentUser AuthUser user,
                                    @RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "all") String filter,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return meetingService.search(user, q, filter, page, size);
    }

    /** Dashboard totals and meetings per month; {@code zone} is an IANA time zone such as Asia/Kolkata. */
    @GetMapping("/stats")
    public MeetingStatsResponse stats(@CurrentUser AuthUser user,
                                      @RequestParam(defaultValue = "UTC") String zone,
                                      @RequestParam(defaultValue = "6") int months) {
        return meetingService.stats(user, zone, months);
    }

    @GetMapping("/{id}")
    public MeetingResponse get(@CurrentUser AuthUser user, @PathVariable Long id) {
        return meetingService.get(user, id);
    }

    @PutMapping("/{id}")
    public MeetingResponse update(@CurrentUser AuthUser user, @PathVariable Long id,
                                  @Valid @RequestBody UpdateMeetingRequest request) {
        return meetingService.update(user, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthUser user, @PathVariable Long id) {
        meetingService.delete(user, id);
    }

    @PostMapping("/{id}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipantResponse addParticipant(@CurrentUser AuthUser user, @PathVariable Long id,
                                              @Valid @RequestBody ParticipantRequest request) {
        return meetingService.addParticipant(user, id, request);
    }

    @DeleteMapping("/{id}/participants/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(@CurrentUser AuthUser user, @PathVariable Long id,
                                  @PathVariable Long participantId) {
        meetingService.removeParticipant(user, id, participantId);
    }
}
