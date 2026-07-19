package com.enthusia.enthusiacurrency.service;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyAmountParserTest {

    @Test
    void acceptsWholePositiveAmounts() {
        assertThat(CurrencyAmountParser.parseUserAmount(" 42 ", false)).isEqualTo(OptionalLong.of(42));
        assertThat(CurrencyAmountParser.parseUserAmount(Long.MAX_VALUE + ".0", false)).isEqualTo(OptionalLong.of(Long.MAX_VALUE));
    }

    @Test
    void rejectsFractionsWhenDecimalsAreDisabled() {
        assertThat(CurrencyAmountParser.parseUserAmount("2.5", false)).isEmpty();
    }

    @Test
    void truncatesPositiveFractionsWhenDecimalsAreEnabled() {
        assertThat(CurrencyAmountParser.parseUserAmount("2.9", true)).isEqualTo(OptionalLong.of(2));
        assertThat(CurrencyAmountParser.parseUserAmount("0.9", true)).isEmpty();
    }

    @Test
    void rejectsNonPositiveMalformedAndOverflowingAmounts() {
        assertThat(CurrencyAmountParser.parseUserAmount("0", true)).isEmpty();
        assertThat(CurrencyAmountParser.parseUserAmount("-1", true)).isEmpty();
        assertThat(CurrencyAmountParser.parseUserAmount("not-a-number", true)).isEmpty();
        assertThat(CurrencyAmountParser.parseUserAmount("9223372036854775808", true)).isEmpty();
    }
}
