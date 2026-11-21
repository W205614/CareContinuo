package app.carecontinuo.scheduling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class AppointmentWindowTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-21T10:00:00+08:00");
    private final AppointmentWindow window = new AppointmentWindow(START);

    @Test
    void bookingEndsExactlyThirtyMinutesBeforeStart() {
        assertTrue(window.acceptsBookingChangesAt(START.minusMinutes(30).minusNanos(1)));
        assertFalse(window.acceptsBookingChangesAt(START.minusMinutes(30)));
    }

    @Test
    void checkInWindowIncludesStartAndExcludesEnd() {
        assertFalse(window.acceptsCheckInAt(START.minusMinutes(15).minusNanos(1)));
        assertTrue(window.acceptsCheckInAt(START.minusMinutes(15)));
        assertTrue(window.acceptsCheckInAt(START.plusMinutes(15).minusNanos(1)));
        assertFalse(window.acceptsCheckInAt(START.plusMinutes(15)));
    }
}
