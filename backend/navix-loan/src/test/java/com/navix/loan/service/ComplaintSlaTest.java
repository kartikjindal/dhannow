package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** PLAN Phase 7, CC-64: complaint SLA due dates count working days in IST; resolution counts calendar days. */
class ComplaintSlaTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static Instant ist(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(IST).toInstant();
    }

    private static Instant endOfDay(int y, int m, int d) {
        return LocalDate.of(y, m, d).atTime(23, 59, 59).atZone(IST).toInstant();
    }

    @Test
    void cc64_aMidweekComplaintIsAcknowledgedInTwoWorkingDaysAndReviewedInFive() {
        // Tuesday 3 Nov 2026, 10:00 IST.
        var due = ComplaintSla.dueDates(ist(2026, 11, 3, 10, 0), Set.of());
        assertThat(due.ackDueAt()).isEqualTo(endOfDay(2026, 11, 5));       // Thu
        assertThat(due.reviewDueAt()).isEqualTo(endOfDay(2026, 11, 10));   // Tue (skips Sat 7, Sun 8)
        assertThat(due.resolveDueAt()).isEqualTo(endOfDay(2026, 11, 18));  // +15 calendar days
    }

    @Test
    void cc64_aFridayComplaintSkipsTheWeekend() {
        var due = ComplaintSla.dueDates(ist(2026, 11, 6, 18, 0), Set.of());
        assertThat(due.ackDueAt()).isEqualTo(endOfDay(2026, 11, 10));      // Mon 9 = 1, Tue 10 = 2
        assertThat(due.reviewDueAt()).isEqualTo(endOfDay(2026, 11, 13));   // Fri 13
        assertThat(due.resolveDueAt()).isEqualTo(endOfDay(2026, 11, 21));  // a Saturday: calendar days do not shift
    }

    @Test
    void cc64_aWeekendComplaintStartsCountingOnMonday() {
        var due = ComplaintSla.dueDates(ist(2026, 11, 7, 9, 0), Set.of());   // Saturday
        assertThat(due.ackDueAt()).isEqualTo(endOfDay(2026, 11, 10));
    }

    @Test
    void cc64_holidaysFromTheCalendarAreNotWorkingDays() {
        // Diwali on Mon 9 Nov 2026 (in the calendar): a Friday complaint's acknowledgement moves to Wednesday.
        var due = ComplaintSla.dueDates(ist(2026, 11, 6, 18, 0), Set.of(LocalDate.of(2026, 11, 9)));
        assertThat(due.ackDueAt()).isEqualTo(endOfDay(2026, 11, 11));
        assertThat(due.reviewDueAt()).isEqualTo(endOfDay(2026, 11, 16));
        assertThat(due.resolveDueAt()).isEqualTo(endOfDay(2026, 11, 21));
    }

    @Test
    void cc64_theDayIsTheIstDateNotTheUtcDate() {
        // 23:30 IST on Tue 3 Nov is 18:00 UTC the same day; 00:30 IST on Wed 4 Nov is still 3 Nov in UTC.
        var lateTuesday = ComplaintSla.dueDates(ist(2026, 11, 3, 23, 30), Set.of());
        var earlyWednesday = ComplaintSla.dueDates(ist(2026, 11, 4, 0, 30), Set.of());
        assertThat(lateTuesday.ackDueAt()).isEqualTo(endOfDay(2026, 11, 5));
        assertThat(earlyWednesday.ackDueAt()).isEqualTo(endOfDay(2026, 11, 6));
    }
}
