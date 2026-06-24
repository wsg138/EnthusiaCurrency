package com.enthusia.enthusiacurrency.analytics;

public enum CurrencyAnalyticsAction {
    DEPOSIT("Deposit"),
    WITHDRAW("Withdraw"),
    PAY("Payment"),
    PAY_FAILED("Payment Failed"),
    WITHDRAW_FAILED("Withdraw Failed");

    private final String displayLabel;

    CurrencyAnalyticsAction(String label) {
        this.displayLabel = label;
    }

    public String label() {
        return displayLabel;
    }
}
