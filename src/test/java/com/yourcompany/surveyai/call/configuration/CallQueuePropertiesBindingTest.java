package com.yourcompany.surveyai.call.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;

class CallQueuePropertiesBindingTest {

    @Test
    void bindsValuesInTheFormatUsedByApplicationYmlAndEnvironmentVariables() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "surveyai.calling.queue.max-concurrent-calls-per-operation", "2",
                "surveyai.calling.queue.retry-delay", "2h",
                "surveyai.calling.queue.dispatch-retry-delay", "5m",
                "surveyai.calling.queue.stale-active-call-after", "1h",
                "surveyai.calling.queue.calling-hours.start", "09:30",
                "surveyai.calling.queue.calling-hours.end", "21:00",
                "surveyai.calling.queue.calling-hours.days", "MONDAY,SATURDAY,SUNDAY",
                "surveyai.calling.queue.calling-hours.default-timezone", "Europe/Istanbul"
        ));
        Binder binder = new Binder(List.of(source), null, ApplicationConversionService.getSharedInstance());

        CallQueueProperties properties = binder.bind("surveyai.calling.queue", CallQueueProperties.class).get();

        assertThat(properties.getMaxConcurrentCallsPerOperation()).isEqualTo(2);
        assertThat(properties.getRetryDelay()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getDispatchRetryDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getStaleActiveCallAfter()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getCallingHours().getStart()).isEqualTo(LocalTime.of(9, 30));
        assertThat(properties.getCallingHours().getEnd()).isEqualTo(LocalTime.of(21, 0));
        assertThat(properties.getCallingHours().getDays())
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }
}
