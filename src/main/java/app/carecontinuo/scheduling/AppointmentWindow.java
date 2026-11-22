package app.carecontinuo.scheduling;

import java.time.OffsetDateTime;

public record AppointmentWindow(OffsetDateTime startsAt) {
    static final int BOOKING_CUTOFF_MINUTES = 30;
    static final int CHECK_IN_WINDOW_MINUTES = 15;

    public boolean acceptsBookingChangesAt(OffsetDateTime time) {
        return time.isBefore(bookingChangesCloseAt());
    }

    public OffsetDateTime bookingChangesCloseAt() {
        return startsAt.minusMinutes(BOOKING_CUTOFF_MINUTES);
    }

    boolean acceptsCheckInAt(OffsetDateTime time) {
        return !time.isBefore(checkInOpensAt()) && time.isBefore(checkInClosesAt());
    }

    OffsetDateTime checkInOpensAt() {
        return startsAt.minusMinutes(CHECK_IN_WINDOW_MINUTES);
    }

    OffsetDateTime checkInClosesAt() {
        return startsAt.plusMinutes(CHECK_IN_WINDOW_MINUTES);
    }
}
