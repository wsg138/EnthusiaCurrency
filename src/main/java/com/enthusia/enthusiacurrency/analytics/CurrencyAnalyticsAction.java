package com.enthusia.enthusiacurrency.analytics;

public enum CurrencyAnalyticsAction {
    DEPOSIT("Deposit"),
    WITHDRAW("Withdraw"),
    PAY("Payment"),
    PAY_FAILED("Payment Failed"),
    WITHDRAW_FAILED("Withdraw Failed");

    private final String label;

    CurrencyAnalyticsAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
