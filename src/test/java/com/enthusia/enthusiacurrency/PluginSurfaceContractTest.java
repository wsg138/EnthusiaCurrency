package com.enthusia.enthusiacurrency;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginSurfaceContractTest {

    @Test
    void pluginDescriptorRetainsReviewedIdentityDependenciesCommandsAndAdminPermissions() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input);
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(yaml.contains("name: EnthusiaCurrency"));
            assertTrue(yaml.contains("main: com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin"));
            assertTrue(yaml.contains("api-version: '1.21'"));
            assertTrue(yaml.contains("depend: [Vault]"));
            assertTrue(yaml.contains("softdepend: [PlaceholderAPI, Plan]"));
            assertTrue(yaml.contains("  balance:\n"));
            assertTrue(yaml.contains("  deposit:\n"));
            assertTrue(yaml.contains("  withdraw:\n"));
            assertTrue(yaml.contains("  pay:\n"));
            assertTrue(yaml.contains("  baltop:\n"));
            assertTrue(yaml.contains("  currency:\n"));
            assertTrue(yaml.contains("  currency.admin:\n    default: op"));
            assertTrue(yaml.contains("  currency.balance.others:\n    default: op"));
        }
    }
}
