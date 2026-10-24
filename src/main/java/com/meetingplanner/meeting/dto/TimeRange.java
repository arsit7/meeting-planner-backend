package com.meetingplanner.meeting.dto;

import java.time.Instant;

/** Implemented by request DTOs that carry a start/end pair validated by {@link ValidTimeRange}. */
public interface TimeRange {

    Instant startTime();

    Instant endTime();
}
