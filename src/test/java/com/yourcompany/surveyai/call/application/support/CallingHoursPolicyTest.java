package com.yourcompany.surveyai.call.application.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.yourcompany.surveyai.call.configuration.CallQueueProperties;
import com.yourcompany.surveyai.common.domain.entity.Company;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CallingHoursPolicyTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final CallQueueProperties properties = new CallQueueProperties();
    private final CallingHoursPolicy policy = new CallingHoursPolicy(properties);

    @Test
    void allowsCallsInsideDefaultWindowIncludingSunday() {
        Company company = company("Europe/Istanbul", Map.of());

        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.SUNDAY, 10, 0))).isTrue();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.SUNDAY, 19, 59))).isTrue();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.SUNDAY, 20, 0))).isFalse();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.MONDAY, 9, 59))).isFalse();
    }

    @Test
    void evaluatesTheWindowInTheCompanyTimezone() {
        Company company = company("Europe/London", Map.of());
        // 10:30 in Istanbul is 08:30 in London (UTC+1 in summer), before the window opens.
        Instant istanbulMorning = ZonedDateTime.of(2026, 7, 6, 10, 30, 0, 0, ISTANBUL).toInstant();

        assertThat(policy.isWithinCallingHours(company, istanbulMorning)).isFalse();
    }

    @Test
    void usesCompanyOverrideForHoursAndDays() {
        Company company = company("Europe/Istanbul", Map.of(
                CallingHoursPolicy.COMPANY_SETTINGS_KEY, Map.of(
                        "start", "09:00",
                        "end", "12:00",
                        "days", List.of("monday", "tuesday")
                )
        ));

        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.MONDAY, 9, 30))).isTrue();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.MONDAY, 13, 0))).isFalse();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.SUNDAY, 9, 30))).isFalse();
    }

    @Test
    void supportsWindowsThatCrossMidnight() {
        properties.getCallingHours().setStart(LocalTime.of(18, 0));
        properties.getCallingHours().setEnd(LocalTime.of(2, 0));
        Company company = company("Europe/Istanbul", Map.of());

        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.FRIDAY, 23, 0))).isTrue();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.FRIDAY, 1, 0))).isTrue();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.FRIDAY, 12, 0))).isFalse();
    }

    @Test
    void fallsBackToDefaultsWhenOverrideOrTimezoneIsInvalid() {
        Company company = company("Not/AZone", Map.of(
                CallingHoursPolicy.COMPANY_SETTINGS_KEY, Map.of("start", "nine", "days", List.of("someday"))
        ));

        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.WEDNESDAY, 11, 0))).isTrue();
        assertThat(policy.isWithinCallingHours(company, at(DayOfWeek.WEDNESDAY, 21, 0))).isFalse();
    }

    private Company company(String timezone, Map<String, Object> metadata) {
        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setTimezone(timezone);
        company.setMetadataJson(metadata);
        return company;
    }

    // A fixed week in July 2026, so the day of week does not depend on when the test runs.
    private Instant at(DayOfWeek day, int hour, int minute) {
        ZonedDateTime monday = ZonedDateTime.of(2026, 7, 6, hour, minute, 0, 0, ISTANBUL);
        return monday.plusDays(day.getValue() - 1L).toInstant();
    }
}
