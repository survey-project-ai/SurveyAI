package com.yourcompany.surveyai.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.i18n.LocaleContextHolder;

class ValidationMessageConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(ValidationMessageConfig.class);

    @AfterEach
    void resetLocaleContext() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void interpolatesValidationMessagesInTurkishForAnEnglishRequest() {
        Locale jvmDefault = Locale.getDefault();
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        contextRunner.run(context -> {
            Validator validator = context.getBean(Validator.class);

            assertThat(validator.validate(new Sample(" ", OffsetDateTime.now().minusDays(1))))
                    .extracting(ConstraintViolation::getMessage)
                    .containsExactlyInAnyOrder("boş değer olamaz", "bugün ya da ileri bir tarih olmalı");
        });

        assertThat(Locale.getDefault()).isEqualTo(jvmDefault);
    }

    private record Sample(@NotBlank String name, @FutureOrPresent OffsetDateTime scheduledAt) {
    }
}
