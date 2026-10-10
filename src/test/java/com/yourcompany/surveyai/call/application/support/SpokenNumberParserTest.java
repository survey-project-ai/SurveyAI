package com.yourcompany.surveyai.call.application.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpokenNumberParserTest {

    @ParameterizedTest
    @CsvSource({
            "42, 42",
            "1985, 1985",
            "on bes, 15",
            "on sekiz yasindayim, 18",
            "yirmi yedi, 27",
            "otuz iki yasindayim, 32",
            "yuz elli, 150",
            "iki yuz otuz, 230",
            "iki bin, 2000",
            "bin dokuz yuz seksen bes, 1985",
            "iki bin yirmi dort, 2024",
            "bir, 1",
            "sifir, 0",
            "bir dort diyelim, 4",
            "bes alti, 5",
            "10 uzerinden 8, 8",
            "on uzerinden sekiz, 8",
            "8 out of 10, 8",
            "twenty five, 25",
            "one hundred and twelve, 112",
            "puanim 7, 7"
    })
    void readsSpokenNumbers(String normalizedText, int expected) {
        assertThat(SpokenNumberParser.parse(normalizedText)).isEqualTo(expected);
    }

    @Test
    void returnsNullWhenNoNumberIsSaid() {
        assertThat(SpokenNumberParser.parse("bilmiyorum")).isNull();
        assertThat(SpokenNumberParser.parse("")).isNull();
        assertThat(SpokenNumberParser.parse(null)).isNull();
    }
}
