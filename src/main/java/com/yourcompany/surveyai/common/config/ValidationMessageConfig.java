package com.yourcompany.surveyai.common.config;

import jakarta.validation.MessageInterpolator;
import jakarta.validation.spi.ConfigurationState;
import java.util.Locale;
import org.springframework.boot.autoconfigure.validation.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Bean Validation messages reach the user in the details of a 400 ("name: must not be blank"), so they are
// interpolated in Turkish from the Turkish bundle Hibernate Validator ships (keys it lacks are filled in by
// ValidationMessages_tr.properties). Only the validator's locale is fixed: the JVM default locale stays as it
// is, because the toLowerCase()/toUpperCase() calls in the code depend on it.
@Configuration
public class ValidationMessageConfig {

    static final Locale MESSAGE_LOCALE = Locale.forLanguageTag("tr");

    // Spring's validator hands the interpolator the request locale (the browser's Accept-Language, else the
    // JVM default) on every call, so Hibernate Validator's own default locale is never consulted. The
    // configured interpolator is wrapped instead, which keeps Spring's MessageSource lookup in front of it.
    @Bean
    public ValidationConfigurationCustomizer turkishValidationMessages() {
        return configuration -> {
            MessageInterpolator configured = configuration instanceof ConfigurationState state
                    ? state.getMessageInterpolator()
                    : configuration.getDefaultMessageInterpolator();
            configuration.messageInterpolator(new FixedLocaleMessageInterpolator(configured, MESSAGE_LOCALE));
        };
    }

    private record FixedLocaleMessageInterpolator(MessageInterpolator delegate, Locale locale)
            implements MessageInterpolator {

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return delegate.interpolate(messageTemplate, context, locale);
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale requestedLocale) {
            return delegate.interpolate(messageTemplate, context, requestedLocale);
        }
    }
}
