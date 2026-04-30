package com.enthusia.enthusiacurrency.plan;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.annotation.Tab;
import com.djrapitops.plan.extension.icon.Color;
import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.analytics.CurrencyAnalyticsSummary;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Locale;

@PluginInfo(
        name = "EnthusiaCurrency",
        iconName = "coins",
        color = Color.AMBER
)
public final class EnthusiaCurrencyPlanExtension implements DataExtension {

    private static final Duration MONTH = Duration.ofDays(30);

    private final EnthusiaCurrencyPlugin plugin;
    private final DecimalFormat decimalFormat = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    public EnthusiaCurrencyPlanExtension(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[]{
                CallEvents.PLAYER_JOIN,
                CallEvents.PLAYER_LEAVE
        };
    }

    @NumberProvider(text = "Stored Accounts", iconName = "users", iconColor = Color.BLUE, priority = 100)
    @Tab("Server")
    public long storedAccounts() {
        return plugin.getBalanceStorage().getAllBalancesSnapshot().size();
    }

    @NumberProvider(text = "Total Bank Currency", iconName = "vault", iconColor = Color.AMBER, priority = 99)
    @Tab("Server")
    public long totalBankCurrency() {
        return plugin.getBalanceStorage().getAllBalancesSnapshot().values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    @StringProvider(text = "Average Bank Balance", iconName = "chart-column", iconColor = Color.BLUE, priority = 98)
    @Tab("Server")
    public String averageBankBalance() {
        long storedAccounts = plugin.getBalanceStorage().getAllBalancesSnapshot().size();
        if (storedAccounts <= 0L) {
            return "0.00 " + plugin.getCurrencyPlural();
        }

        long totalBankCurrency = plugin.getBalanceStorage().getAllBalancesSnapshot().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        double average = (double) totalBankCurrency / (double) storedAccounts;
        return decimalFormat.format(average) + " " + plugin.getCurrencyPlural();
    }

    @NumberProvider(
            text = "Server Last Currency Activity",
            description = "Most recent deposit, withdrawal, or payment seen across the server.",
            format = FormatType.DATE_SECOND,
            iconName = "clock",
            iconColor = Color.LIGHT_BLUE,
            priority = 97
    )
    @Tab("Server")
    public long serverLastCurrencyActivity() {
        return plugin.getCurrencyAnalyticsStorage().getServerTotals().lastActivityAt();
    }

    @NumberProvider(text = "Deposited Total", iconName = "circle-plus", iconColor = Color.GREEN, priority = 96)
    @Tab("Server")
    public long serverDepositedTotal() {
        return plugin.getCurrencyAnalyticsStorage().getServerTotals().deposited();
    }

    @NumberProvider(text = "Deposited 30d", iconName = "circle-plus", iconColor = Color.GREEN, priority = 95)
    @Tab("Server")
    public long serverDeposited30d() {
        return serverSummary(MONTH).deposited();
    }

    @NumberProvider(text = "Withdrawn Total", iconName = "circle-minus", iconColor = Color.ORANGE, priority = 94)
    @Tab("Server")
    public long serverWithdrawnTotal() {
        return plugin.getCurrencyAnalyticsStorage().getServerTotals().withdrawn();
    }

    @NumberProvider(text = "Withdrawn 30d", iconName = "circle-minus", iconColor = Color.ORANGE, priority = 93)
    @Tab("Server")
    public long serverWithdrawn30d() {
        return serverSummary(MONTH).withdrawn();
    }

    private CurrencyAnalyticsSummary serverSummary(Duration window) {
        return plugin.getCurrencyAnalyticsStorage().summarizeServer(window);
    }
}
