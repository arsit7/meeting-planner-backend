package com.meetingplanner.meeting.dto;

import java.util.List;

/**
 * Dashboard figures over every meeting the user can see. {@code months} is oldest first and ends with the
 * current month, each keyed {@code YYYY-MM} in the requested time zone.
 */
public record MeetingStatsResponse(
        long total,
        long upcoming,
        long completed,
        long organized,
        long invited,
        long last30Days,
        long previous30Days,
        List<MonthCount> months) {

    /** Meetings starting in one calendar month; {@code participants} is the number of people invited. */
    public record MonthCount(String month, long organized, long invited, long participants) {
    }
}
