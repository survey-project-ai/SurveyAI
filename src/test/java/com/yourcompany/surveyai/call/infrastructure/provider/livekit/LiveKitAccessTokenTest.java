package com.yourcompany.surveyai.call.infrastructure.provider.livekit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class LiveKitAccessTokenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void create_signsTheGrantWithTheApiSecretTheWayLiveKitExpects() throws Exception {
        Instant now = Instant.ofEpochSecond(1_792_000_000L);

        String token = LiveKitAccessToken.create(
                "APIkey", "api-secret", Map.of("roomAdmin", true, "room", "call-1"), Duration.ofMinutes(10), now, objectMapper);

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asText()).isEqualTo("HS256");
        assertThat(claims.path("iss").asText()).isEqualTo("APIkey");
        assertThat(claims.path("nbf").asLong()).isEqualTo(1_792_000_000L);
        assertThat(claims.path("exp").asLong()).isEqualTo(1_792_000_600L);
        assertThat(claims.path("video").path("roomAdmin").asBoolean()).isTrue();
        assertThat(claims.path("video").path("room").asText()).isEqualTo("call-1");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("api-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(Base64.getUrlDecoder().decode(parts[2])).isEqualTo(expected);
        assertThat(token).doesNotContain("=");
    }
}
