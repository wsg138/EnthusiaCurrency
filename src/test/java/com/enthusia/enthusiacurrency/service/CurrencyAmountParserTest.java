package com.enthusia.enthusiacurrency.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CurrencyAmountParserTest {

    @Test
    void parsesPositiveWholeAmountsAndTrimsWhitespace() {
        assertEquals(OptionalLong.of(1L), CurrencyAmountParser.parseUserAmount("1", false));
        assertEquals(OptionalLong.of(42L), CurrencyAmountParser.parseUserAmount(" 42 ", false));
        assertEquals(OptionalLong.of(1000L), CurrencyAmountParser.parseUserAmount("1e3", false));
    }

    @Test
    void wholeOnlyModeAcceptsEquivalentDecimalNotationButRejectsFractions() {
        assertEquals(OptionalLong.of(5L), CurrencyAmountParser.parseUserAmount("5.0", false));
        assertEquals(OptionalLong.of(5L), CurrencyAmountParser.parseUserAmount("5.000", false));
        assertTrue(CurrencyAmountParser.parseUserAmount("5.1", false).isEmpty());
        assertTrue(CurrencyAmountParser.parseUserAmount("0.5", false).isEmpty());
    }

    @Test
    void decimalModeRoundsDownOnlyAfterRejectingNonPositiveValues() {
        assertEquals(OptionalLong.of(7L), CurrencyAmountParser.parseUserAmount("7.99", true));
        assertTrue(CurrencyAmountParser.parseUserAmount("0.99", true).isEmpty());
        assertTrue(CurrencyAmountParser.parseUserAmount("0", true).isEmpty());
        assertTrue(CurrencyAmountParser.parseUserAmount("-1.5", true).isEmpty());
    }

    @Test
    void malformedAndOverflowingAmountsFailClosed() {
        assertTrue(CurrencyAmountParser.parseUserAmount("", false).isEmpty());
        assertTrue(CurrencyAmountParser.parseUserAmount("not-money", false).isEmpty());
        assertTrue(CurrencyAmountParser.parseUserAmount("9223372036854775808", false).isEmpty());
        assertTrue(CurrencyAmountParser.parseUserAmount("1E100", true).isEmpty());
    }
}
