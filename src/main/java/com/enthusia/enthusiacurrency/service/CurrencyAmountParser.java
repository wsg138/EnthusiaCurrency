package com.enthusia.enthusiacurrency.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;

public final class CurrencyAmountParser {

    private CurrencyAmountParser() {
    }

    public static OptionalLong parseUserAmount(String raw, boolean allowDecimals) {
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() <= 0) {
                return OptionalLong.empty();
            }

            if (!allowDecimals && value.stripTrailingZeros().scale() > 0) {
                return OptionalLong.empty();
            }

            BigDecimal wholeValue = value.setScale(0, RoundingMode.DOWN);
            if (wholeValue.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                return OptionalLong.empty();
            }

            long parsed = wholeValue.longValueExact();
            if (parsed <= 0) {
                return OptionalLong.empty();
            }

            return OptionalLong.of(parsed);
        } catch (NumberFormatException | ArithmeticException ex) {
            return OptionalLong.empty();
        }
    }
}
