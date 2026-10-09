package com.yourcompany.surveyai.call.application.support;

import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
import com.yourcompany.surveyai.common.domain.entity.Company;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Decides whether new calls may be placed for a company right now. The window comes from
// surveyai.calling.queue.calling-hours and can be overridden per company with a "callingHours"
// object in company.metadata_json, e.g. {"start": "09:00", "end": "21:00", "days": ["MONDAY", "SUNDAY"]}.
// Times are evaluated in the company's timezone.
@Component
public class CallingHoursPolicy {

    public static final String COMPANY_SETTINGS_KEY = "callingHours";

    private static final Logger log = LoggerFactory.getLogger(CallingHoursPolicy.class);

    private final CallQueueProperties properties;

    public CallingHoursPolicy(CallQueueProperties properties) {
        this.properties = properties;
    }

    public boolean isWithinCallingHours(Company company, Instant now) {
        CallQueueProperties.CallingHours defaults = properties.getCallingHours();
        Map<?, ?> override = companyOverride(company);

        LocalTime start = parseTime(override.get("start"), defaults.getStart());
        LocalTime end = parseTime(override.get("end"), defaults.getEnd());
        Set<DayOfWeek> days = parseDays(override.get("days"), defaults.getDays());
        ZonedDateTime localNow = now.atZone(resolveZone(company, defaults.getDefaultTimezone()));

        if (!days.contains(localNow.getDayOfWeek())) {
            return false;
        }
        if (start.equals(end)) {
            return true;
        }

        LocalTime time = localNow.toLocalTime();
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        // A window that crosses midnight, e.g. 18:00-02:00.
        return !time.isBefore(start) || time.isBefore(end);
    }

    private Map<?, ?> companyOverride(Company company) {
        if (company == null || company.getMetadataJson() == null) {
            return Map.of();
        }
        Object value = company.getMetadataJson().get(COMPANY_SETTINGS_KEY);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private LocalTime parseTime(Object value, LocalTime fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return LocalTime.parse(value.toString().trim());
        } catch (DateTimeException error) {
            log.warn("Ignoring invalid calling hours time. value={}", value);
            return fallback;
        }
    }

    private Set<DayOfWeek> parseDays(Object value, List<DayOfWeek> fallback) {
        Set<DayOfWeek> fallbackDays = fallback == null || fallback.isEmpty()
                ? EnumSet.allOf(DayOfWeek.class)
                : EnumSet.copyOf(fallback);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return fallbackDays;
        }
        try {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (Object day : list) {
                days.add(DayOfWeek.valueOf(day.toString().trim().toUpperCase(Locale.ROOT)));
            }
            return days;
        } catch (IllegalArgumentException error) {
            log.warn("Ignoring invalid calling days. value={}", value);
            return fallbackDays;
        }
    }

    private ZoneId resolveZone(Company company, String defaultTimezone) {
        String timezone = company == null ? null : company.getTimezone();
        if (timezone != null && !timezone.isBlank()) {
            try {
                return ZoneId.of(timezone.trim());
            } catch (DateTimeException error) {
                log.warn("Ignoring invalid company timezone. companyId={} timezone={}", company.getId(), timezone);
            }
        }
        return ZoneId.of(defaultTimezone);
    }
}
