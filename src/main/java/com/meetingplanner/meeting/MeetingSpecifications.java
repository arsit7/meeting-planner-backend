package com.meetingplanner.meeting;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;

/** Building blocks for the meeting list and stats queries; combine them with {@code and(...)}. */
final class MeetingSpecifications {

    private MeetingSpecifications() {
    }

    /** No restriction. */
    static Specification<Meeting> any() {
        return (root, query, cb) -> null;
    }

    /** Meetings the user created, plus meetings where the user's e-mail is on the participant list. */
    static Specification<Meeting> accessibleTo(Long userId, String email) {
        return (root, query, cb) -> {
            Subquery<Long> invited = query.subquery(Long.class);
            Root<Participant> participant = invited.from(Participant.class);
            invited.select(participant.get("id"))
                    .where(cb.equal(participant.get("meeting"), root), cb.equal(participant.get("email"), email));
            return cb.or(cb.equal(root.get("createdBy").get("id"), userId), cb.exists(invited));
        };
    }

    static Specification<Meeting> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("createdBy").get("id"), userId);
    }

    static Specification<Meeting> notOwnedBy(Long userId) {
        return (root, query, cb) -> cb.notEqual(root.get("createdBy").get("id"), userId);
    }

    /** Complete: the end time has been reached (same rule as {@link Meeting#hasEnded}). */
    static Specification<Meeting> endedAt(Instant now) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("endTime"), now);
    }

    static Specification<Meeting> notEndedAt(Instant now) {
        return (root, query, cb) -> cb.greaterThan(root.get("endTime"), now);
    }

    /** Start time in {@code [from, to)}. */
    static Specification<Meeting> startsBetween(Instant from, Instant to) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("startTime"), from),
                cb.lessThan(root.get("startTime"), to));
    }

    /** Case-insensitive substring match on the title or the organizer's name; blank matches everything. */
    static Specification<Meeting> matching(String term) {
        if (term == null || term.isBlank()) {
            return any();
        }
        String pattern = "%" + escapeLike(term.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("createdBy").get("name")), pattern, '\\'));
    }

    /**
     * Orders upcoming meetings first (soonest first), then completed ones (most recent first), with the id as
     * a tie-breaker so pages stay stable. Adds no filter. Skipped for the count query, which has no ordering.
     */
    static Specification<Meeting> upcomingFirst(Instant now) {
        return (root, query, cb) -> {
            Class<?> resultType = query.getResultType();
            if (resultType == Long.class || resultType == long.class) {
                return null;
            }
            Expression<Integer> completed = cb.<Integer>selectCase()
                    .when(cb.lessThanOrEqualTo(root.get("endTime"), now), 1)
                    .otherwise(0);
            Expression<Instant> upcomingStart = cb.<Instant>selectCase()
                    .when(cb.greaterThan(root.get("endTime"), now), root.<Instant>get("startTime"))
                    .otherwise(cb.nullLiteral(Instant.class));
            query.orderBy(
                    cb.asc(completed),
                    cb.asc(upcomingStart),
                    cb.desc(root.get("startTime")),
                    cb.asc(root.get("id")));
            return null;
        };
    }

    /** Escapes LIKE wildcards so a search for "50%" means the literal text. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
