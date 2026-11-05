package com.imsvet.ims.reporting.application;

import com.imsvet.ims.common.error.ValidationException;
import com.imsvet.ims.common.error.ValidationException.Violation;
import com.imsvet.ims.common.time.BusinessCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A report period (api.md §4.13): business dates {@code from} and {@code to}, both inclusive; without {@code to}
 * today, without {@code from} the first day of {@code to}'s month; {@code from} after {@code to} is refused. {@code start}
 * and {@code end} are the half-open range of instants {@code [start, end)} in the business time zone.
 */
record ReportPeriod(LocalDate from, LocalDate to, Instant start, Instant end) {

    static ReportPeriod of(LocalDate from, LocalDate to, BusinessCalendar calendar) {
        LocalDate last = to == null ? calendar.today() : to;
        LocalDate first = from == null ? last.withDayOfMonth(1) : from;
        if (first.isAfter(last)) {
            throw new ValidationException("The period is invalid.",
                    List.of(new Violation("from", "RANGE", "The start date must not be after the end date.")));
        }
        return new ReportPeriod(first, last, calendar.startOf(first), calendar.startOf(last.plusDays(1)));
    }
}
