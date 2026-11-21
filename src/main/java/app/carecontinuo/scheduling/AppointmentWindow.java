package app.carecontinuo.scheduling;

import java.time.OffsetDateTime;

record AppointmentWindow(OffsetDateTime startsAt) {
    static final int BOOKING_CUTOFF_MINUTES = 30;
    static final int CHECK_IN_WINDOW_MINUTES = 15;

    boolean acceptsBookingChangesAt(OffsetDateTime time) {
        return time.isBefore(startsAt.minusMinutes(BOOKING_CUTOFF_MINUTES));
    }

    boolean acceptsCheckInAt(OffsetDateTime time) {
        return !time.isBefore(startsAt.minusMinutes(CHECK_IN_WINDOW_MINUTES))
                && time.isBefore(startsAt.plusMinutes(CHECK_IN_WINDOW_MINUTES));
    }
}
