package com.enthusia.enthusiacurrency.plan;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.ExtensionService;
import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;

public final class PlanIntegrationHook {

    private final EnthusiaCurrencyPlugin plugin;

    public PlanIntegrationHook(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void hookIntoPlan() {
        if (!areCapabilitiesAvailable()) {
            plugin.getLogger().fine("Plan is installed, but required DataExtension capabilities are unavailable.");
            return;
        }

        registerDataExtension();
        CapabilityService.getInstance().registerEnableListener(enabled -> {
            if (enabled) {
                registerDataExtension();
            }
        });
    }

    private boolean areCapabilitiesAvailable() {
        try {
            CapabilityService capabilities = CapabilityService.getInstance();
            return capabilities.hasCapability("DATA_EXTENSION_VALUES")
                    && capabilities.hasCapability("DATA_EXTENSION_TABLES");
        } catch (Throwable ex) {
            return false;
        }
    }

    private void registerDataExtension() {
        try {
            ExtensionService.getInstance().register(new EnthusiaCurrencyPlanExtension(plugin));
            plugin.getLogger().info("Registered EnthusiaCurrency Plan analytics extension.");
        } catch (IllegalStateException ex) {
            plugin.getLogger().fine("Plan is not ready for EnthusiaCurrency analytics registration.");
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Plan rejected the EnthusiaCurrency analytics extension: " + ex.getMessage());
        } catch (Throwable ex) {
            plugin.getLogger().warning("Failed to register EnthusiaCurrency Plan analytics extension: " + ex.getMessage());
        }
    }
}
