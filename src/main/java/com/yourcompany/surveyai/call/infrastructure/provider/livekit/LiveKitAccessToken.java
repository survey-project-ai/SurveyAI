package com.yourcompany.surveyai.call.infrastructure.provider.livekit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.surveyai.common.exception.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// A LiveKit access token: a JWT signed with HS256 by the project's API secret, as LiveKit's own
// server SDKs build it for server API calls (issuer = API key, permissions in the "video" claim).
final class LiveKitAccessToken {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private LiveKitAccessToken() {
    }

    static String create(
            String apiKey,
            String apiSecret,
            Map<String, Object> videoGrant,
            Duration ttl,
            Instant now,
            ObjectMapper objectMapper
    ) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", apiKey);
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", now.plus(ttl).getEpochSecond());
        claims.put("video", videoGrant);

        String signingInput = encode(header, objectMapper) + "." + encode(claims, objectMapper);
        return signingInput + "." + BASE64_URL.encodeToString(sign(signingInput, apiSecret));
    }

    private static String encode(Map<String, Object> value, ObjectMapper objectMapper) {
        try {
            return BASE64_URL.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException error) {
            throw new ValidationException("Failed to build the LiveKit access token");
        }
    }

    private static byte[] sign(String signingInput, String apiSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new ValidationException("Failed to sign the LiveKit access token");
        }
    }
}
