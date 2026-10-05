package com.enthusia.enthusiacurrency;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Inventory guard for the deterministic feature families currently expected to have direct tests. */
class FullFeatureCoverageContractTest {
    private static final Map<String, List<String>> FEATURE_MARKERS = featureMarkers();

    @Test
    void everyReviewedFeatureFamilyHasConcreteRegressionCoverage() throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path testRoot = root.resolve("src/test/java");
        if (!Files.isDirectory(testRoot)) {
            throw new IllegalStateException("Could not locate src/test/java from " + root);
        }

        Set<String> tests;
        try (Stream<Path> files = Files.walk(testRoot)) {
            tests = files
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
                    .filter(path -> path.endsWith("test.java"))
                    .collect(Collectors.toSet());
        }

        Map<String, List<String>> missing = new LinkedHashMap<>();
        FEATURE_MARKERS.forEach((feature, markers) -> {
            boolean covered = markers.stream()
                    .map(marker -> marker.toLowerCase(Locale.ROOT))
                    .anyMatch(marker -> tests.stream().anyMatch(path -> path.contains(marker)));
            if (!covered) {
                missing.put(feature, markers);
            }
        });

        if (!missing.isEmpty()) {
            fail("Reviewed EnthusiaCurrency feature families without concrete regression tests: " + missing);
        }
    }

    private static Map<String, List<String>> featureMarkers() {
        Map<String, List<String>> markers = new LinkedHashMap<>();
        markers.put("plugin metadata/surface", List.of("pluginsurfacecontract"));
        markers.put("user amount parsing", List.of("currencyamountparser"));
        markers.put("moderation state evaluation", List.of("currencymoderationstateevaluator"));
        markers.put("moderation removal allocation", List.of("currencyremovalallocator"));
        markers.put("moderation API snapshots/plans/results", List.of("currencymoderationcontract"));
        markers.put("movement locking", List.of("movementlockregistry"));
        markers.put("physical inventory withdrawal", List.of("currencyinventorywithdrawal"));
        markers.put("item-balance snapshot state", List.of("itembalancesnapshot"));
        markers.put("SQLite bank persistence", List.of("sqlitebalancerepository"));
        return Map.copyOf(markers);
    }
}
