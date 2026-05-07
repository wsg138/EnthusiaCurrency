package com.enthusia.enthusiacurrency.leaderboard;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

public final class R2LeaderboardUploader {

    private static final String SERVICE = "s3";
    private static final String REGION = "auto";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(ZoneOffset.UTC);

    private final EnthusiaCurrencyPlugin plugin;
    private final HttpClient httpClient;
    private final Clock clock;

    public R2LeaderboardUploader(EnthusiaCurrencyPlugin plugin) {
        this(plugin, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    R2LeaderboardUploader(EnthusiaCurrencyPlugin plugin, HttpClient httpClient, Clock clock) {
        this.plugin = plugin;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("leaderboards.export.r2.enabled", false);
    }

    public void uploadJson(String key, String json) {
        if (!isEnabled()) {
            return;
        }

        R2Settings settings = loadSettings();
        if (!settings.isUsable()) {
            plugin.getLogger().warning("R2 leaderboard export is enabled but endpoint, bucket, key, access key, or secret is missing.");
            return;
        }

        try {
            putObject(settings, key, json);
        } catch (Exception ex) {
            plugin.getDebugMetrics().r2Failure();
            plugin.getLogger().log(Level.WARNING, "Failed to upload leaderboard export to R2 key " + key + ": " + ex.getMessage(), ex);
        }
    }

    private R2Settings loadSettings() {
        String endpoint = trimToNull(plugin.getConfig().getString("leaderboards.export.r2.endpoint"));
        String bucket = trimToNull(plugin.getConfig().getString("leaderboards.export.r2.bucket"));
        String accessKeyId = resolveSecret(
                "leaderboards.export.r2.access-key-id",
                "leaderboards.export.r2.access-key-id-env"
        );
        String secretAccessKey = resolveSecret(
                "leaderboards.export.r2.secret-access-key",
                "leaderboards.export.r2.secret-access-key-env"
        );
        return new R2Settings(endpoint, bucket, accessKeyId, secretAccessKey);
    }

    private String resolveSecret(String directPath, String envPath) {
        String directValue = trimToNull(plugin.getConfig().getString(directPath));
        if (directValue != null) {
            return directValue;
        }

        String envName = trimToNull(plugin.getConfig().getString(envPath));
        if (envName == null) {
            return null;
        }
        return trimToNull(System.getenv(envName));
    }

    private void putObject(R2Settings settings, String key, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String payloadHash = sha256Hex(body);
        Instant now = clock.instant();
        String amzDate = AMZ_DATE_FORMAT.format(now);
        String dateStamp = DATE_STAMP_FORMAT.format(now);
        URI endpointUri = URI.create(settings.endpoint());
        String host = Objects.requireNonNull(endpointUri.getHost(), "R2 endpoint host");
        String path = "/" + encodePathSegment(settings.bucket()) + "/" + encodeObjectKey(key);
        URI uri = URI.create(endpointUri.getScheme() + "://" + host + path);

        String canonicalHeaders = ""
                + "cache-control:no-cache\n"
                + "content-type:application/json\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "cache-control;content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = "PUT\n"
                + path + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + REGION + "/" + SERVICE + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hmacHex(signingKey(settings.secretAccessKey(), dateStamp), stringToSign);
        String authorization = ALGORITHM
                + " Credential=" + settings.accessKeyId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(uri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Authorization", authorization)
                .header("Cache-Control", "no-cache")
                .header("Content-Type", "application/json")
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("R2 returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private byte[] signingKey(String secretAccessKey, String dateStamp) throws Exception {
        byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] dateRegionKey = hmac(dateKey, REGION);
        byte[] dateRegionServiceKey = hmac(dateRegionKey, SERVICE);
        return hmac(dateRegionServiceKey, "aws4_request");
    }

    private String encodeObjectKey(String key) {
        String normalized = key.startsWith("/") ? key.substring(1) : key;
        String[] segments = normalized.split("/");
        StringBuilder encoded = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                encoded.append('/');
            }
            encoded.append(encodePathSegment(segments[index]));
        }
        return encoded.toString();
    }

    private String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sha256Hex(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private String hmacHex(byte[] key, String value) throws Exception {
        return HexFormat.of().formatHex(hmac(key, value));
    }

    private byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record R2Settings(String endpoint, String bucket, String accessKeyId, String secretAccessKey) {

        boolean isUsable() {
            return endpoint != null
                    && bucket != null
                    && accessKeyId != null
                    && secretAccessKey != null;
        }
    }
}
