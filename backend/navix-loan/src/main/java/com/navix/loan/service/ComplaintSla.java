package com.navix.loan.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * The complaint SLA (PLAN Phase 7, CC-64), in IST: acknowledged by the end of the 2nd working day after the day it
 * was filed, in review by the end of the 5th, resolved by the end of the 15th calendar day. Working days skip
 * Saturdays, Sundays and the {@code holiday_calendar}.
 */
public final class ComplaintSla {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int ACK_WORKING_DAYS = 2;
    static final int REVIEW_WORKING_DAYS = 5;
    static final int RESOLVE_CALENDAR_DAYS = 15;
    /** Far enough ahead to cover 5 working days across any run of holidays the calendar holds. */
    static final int HOLIDAY_LOOKAHEAD_DAYS = 60;

    public record DueDates(Instant ackDueAt, Instant reviewDueAt, Instant resolveDueAt) {
    }

    private ComplaintSla() {
    }

    public static DueDates dueDates(Instant filedAt, Set<LocalDate> holidays) {
        LocalDate filed = filedAt.atZone(IST).toLocalDate();
        return new DueDates(endOfDay(addWorkingDays(filed, ACK_WORKING_DAYS, holidays)),
                endOfDay(addWorkingDays(filed, REVIEW_WORKING_DAYS, holidays)),
                endOfDay(filed.plusDays(RESOLVE_CALENDAR_DAYS)));
    }

    /** The {@code n}th working day after {@code from} ({@code from} itself never counts). */
    static LocalDate addWorkingDays(LocalDate from, int n, Set<LocalDate> holidays) {
        LocalDate day = from;
        for (int counted = 0; counted < n; ) {
            day = day.plusDays(1);
            if (isWorkingDay(day, holidays)) {
                counted++;
            }
        }
        return day;
    }

    static boolean isWorkingDay(LocalDate day, Set<LocalDate> holidays) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                && !holidays.contains(day);
    }

    private static Instant endOfDay(LocalDate day) {
        return day.atTime(LocalTime.of(23, 59, 59)).atZone(IST).toInstant();
    }
}
