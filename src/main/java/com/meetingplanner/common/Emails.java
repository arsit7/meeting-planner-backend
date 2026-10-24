package com.meetingplanner.common;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** Canonical form used for storage and comparison. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
