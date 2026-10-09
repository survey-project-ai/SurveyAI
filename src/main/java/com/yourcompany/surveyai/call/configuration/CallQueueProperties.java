package com.yourcompany.surveyai.call.configuration;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "surveyai.calling.queue")
public class CallQueueProperties {

    private int maxConcurrentCallsPerOperation = 1;
    private Duration retryDelay = Duration.ofHours(2);
    private Duration dispatchRetryDelay = Duration.ofMinutes(5);
    private Duration staleActiveCallAfter = Duration.ofHours(1);
    private CallingHours callingHours = new CallingHours();

    public int getMaxConcurrentCallsPerOperation() {
        return maxConcurrentCallsPerOperation;
    }

    public void setMaxConcurrentCallsPerOperation(int maxConcurrentCallsPerOperation) {
        this.maxConcurrentCallsPerOperation = maxConcurrentCallsPerOperation;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Duration getDispatchRetryDelay() {
        return dispatchRetryDelay;
    }

    public void setDispatchRetryDelay(Duration dispatchRetryDelay) {
        this.dispatchRetryDelay = dispatchRetryDelay;
    }

    public Duration getStaleActiveCallAfter() {
        return staleActiveCallAfter;
    }

    public void setStaleActiveCallAfter(Duration staleActiveCallAfter) {
        this.staleActiveCallAfter = staleActiveCallAfter;
    }

    public CallingHours getCallingHours() {
        return callingHours;
    }

    public void setCallingHours(CallingHours callingHours) {
        this.callingHours = callingHours;
    }

    public static class CallingHours {

        private LocalTime start = LocalTime.of(10, 0);
        private LocalTime end = LocalTime.of(20, 0);
        private List<DayOfWeek> days = new ArrayList<>(List.of(DayOfWeek.values()));
        private String defaultTimezone = "Europe/Istanbul";

        public LocalTime getStart() {
            return start;
        }

        public void setStart(LocalTime start) {
            this.start = start;
        }

        public LocalTime getEnd() {
            return end;
        }

        public void setEnd(LocalTime end) {
            this.end = end;
        }

        public List<DayOfWeek> getDays() {
            return days;
        }

        public void setDays(List<DayOfWeek> days) {
            this.days = days;
        }

        public String getDefaultTimezone() {
            return defaultTimezone;
        }

        public void setDefaultTimezone(String defaultTimezone) {
            this.defaultTimezone = defaultTimezone;
        }
    }
}
