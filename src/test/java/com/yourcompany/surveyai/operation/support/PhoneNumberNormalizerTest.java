package com.yourcompany.surveyai.operation.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "905321234567, 905321234567",
            "+90 (532) 123 45 67, 905321234567",
            "0532 123 45 67, 905321234567",
            "00905321234567, 905321234567",
            // Excel drops the leading 0 of numeric cells.
            "5321234567, 905321234567",
            "532 123 45 67, 905321234567",
            "2121234567, 902121234567",
            // Explicit international numbers keep their country code.
            "+4915112345678, 4915112345678",
            "+44 20 7946 0958, 442079460958",
            "00442079460958, 442079460958"
    })
    void normalizesToE164DigitsWithTurkeyAsDefaultCountry(String input, String expected) {
        assertThat(PhoneNumberNormalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"905321234567", "902121234567", "4915112345678", "442079460958"})
    void acceptsCompleteNumbers(String normalized) {
        assertThat(PhoneNumberNormalizer.isValid(normalized)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "9053212345", "90532123456", "9053212345678", "1234567", "0905321234567"})
    void rejectsIncompleteOrMalformedNumbers(String normalized) {
        assertThat(PhoneNumberNormalizer.isValid(normalized)).isFalse();
    }
}
