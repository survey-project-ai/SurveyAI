package com.yourcompany.surveyai.call.configuration;

import com.yourcompany.surveyai.call.application.provider.CallProviderRegistry;
import com.yourcompany.surveyai.call.application.provider.ProviderConfigurationValidationResult;
import com.yourcompany.surveyai.call.application.provider.VoiceExecutionProvider;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class VoiceProviderStartupDiagnostics implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VoiceProviderStartupDiagnostics.class);

    private final VoiceProviderConfigurationResolver configurationResolver;
    private final CallProviderRegistry callProviderRegistry;

    public VoiceProviderStartupDiagnostics(
            VoiceProviderConfigurationResolver configurationResolver,
            CallProviderRegistry callProviderRegistry
    ) {
        this.configurationResolver = configurationResolver;
        this.callProviderRegistry = callProviderRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        CallProvider activeProvider = configurationResolver.getActiveProvider();
        VoiceProviderConfiguration configuration = configurationResolver.getActiveConfiguration();
        VoiceExecutionProvider provider = callProviderRegistry.getRequiredProvider(activeProvider);
        ProviderConfigurationValidationResult validation = provider.validateConfiguration(configuration);

        log.info(
                "Voice provider startup diagnostics. activeProvider={} enabled={} mode={} baseUrl={} webhookPath={}",
                activeProvider,
                configuration.enabled(),
                configuration.mode(),
                configuration.baseUrl(),
                "/api/v1/provider-webhooks/" + activeProvider.name()
        );

        if (!validation.valid()) {
            log.warn("Voice provider configuration validation failed for {}: {}", activeProvider, validation.message());
            return;
        }

        if (activeProvider == CallProvider.LIVEKIT) {
            log.info(
                    "LiveKit runtime config. url={} apiKeySuffix={} agentName={} sipTrunkIdPresent={} webhookSecretPresent={} toolApiSecretPresent={} toolApiSecretFingerprint={}",
                    configuration.baseUrl(),
                    redactSuffix(configuration.apiKey()),
                    configuration.agentId(),
                    hasText(configuration.phoneNumberId()),
                    hasText(configuration.webhookSecret()),
                    hasText(configuration.settings().get("tool-api-secret")),
                    fingerprint(configuration.settings().get("tool-api-secret"))
            );
            log.info("The LiveKit voice agent posts call events to /api/v1/provider-webhooks/LIVEKIT and calls the interview tools at /api/v1/provider-tools/interviews/*.");
        }

        if (activeProvider == CallProvider.ELEVENLABS) {
            String publicWebhookBaseUrl = configuration.settings().get("public-webhook-base-url");
            String toolApiSecret = configuration.settings().get("tool-api-secret");
            boolean promptOverrideEnabled = isEnabled(configuration, "agent-prompt-override-enabled");
            boolean firstMessageOverrideEnabled = isEnabled(configuration, "agent-first-message-override-enabled");
            boolean languageOverrideEnabled = isEnabled(configuration, "agent-language-override-enabled");
            log.info(
                    "ElevenLabs runtime config. baseUrl={} outboundEndpoint={} authHeaderType={} apiKeyPresent={} apiKeySuffix={} agentIdPresent={} phoneNumberIdPresent={} webhookBaseUrlPresent={} toolApiSecretPresent={} toolApiSecretPreview={} toolApiSecretFingerprint={} promptOverrideEnabled={} firstMessageOverrideEnabled={} languageOverrideEnabled={}",
                    configuration.baseUrl(),
                    resolveOutboundEndpoint(configuration.baseUrl()),
                    "xi-api-key",
                    hasText(configuration.apiKey()),
                    redactSuffix(configuration.apiKey()),
                    hasText(configuration.agentId()),
                    hasText(configuration.phoneNumberId()),
                    hasText(publicWebhookBaseUrl),
                    hasText(toolApiSecret),
                    redactSecret(toolApiSecret),
                    fingerprint(toolApiSecret),
                    promptOverrideEnabled,
                    firstMessageOverrideEnabled,
                    languageOverrideEnabled
            );

            if (configuration.mockMode()) {
                log.info("ElevenLabs mock mode is enabled. Dispatch requests will not place real calls.");
            } else {
                log.info("ElevenLabs live mode is enabled. Real outbound calls may be placed for started operations.");
            }

            log.info("ElevenLabs outbound calls use the stored agent configuration for agentId={}. Preview-only or unsaved dashboard changes will not affect live outbound calls.", configuration.agentId());
            log.info("ElevenLabs telephony agents should be configured for mu-law 8000 Hz input and output when using Twilio-backed phone calls.");
            if (!promptOverrideEnabled || !firstMessageOverrideEnabled || !languageOverrideEnabled) {
                log.info("Code-side agent overrides are partially or fully disabled. Live calls will rely on the published agent configuration unless the corresponding override flags are enabled and allowed in the agent Security tab.");
            } else {
                log.info("Code-side agent overrides are enabled. Ensure the agent Security tab explicitly allows prompt, first message, and language overrides.");
            }

            if (publicWebhookBaseUrl == null || publicWebhookBaseUrl.isBlank()) {
                log.warn("ELEVENLABS public webhook base URL is not configured. Ensure ElevenLabs can reach /api/v1/provider-webhooks/{} from the public internet.", activeProvider.name());
            } else {
                String normalizedBase = publicWebhookBaseUrl.endsWith("/")
                        ? publicWebhookBaseUrl.substring(0, publicWebhookBaseUrl.length() - 1)
                        : publicWebhookBaseUrl;
                log.info("Expected ElevenLabs webhook URL: {}/api/v1/provider-webhooks/{}", normalizedBase, activeProvider.name());
                log.info("Expected ElevenLabs tool start URL: {}/api/v1/provider-tools/elevenlabs/interviews/start", normalizedBase);
                log.info("Expected ElevenLabs tool current-question URL: {}/api/v1/provider-tools/elevenlabs/interviews/current-question", normalizedBase);
                log.info("Expected ElevenLabs tool answer URL: {}/api/v1/provider-tools/elevenlabs/interviews/answer", normalizedBase);
                log.info("Expected ElevenLabs tool finish URL: {}/api/v1/provider-tools/elevenlabs/interviews/finish", normalizedBase);
            }
        }
    }

    private String resolveOutboundEndpoint(String baseUrl) {
        if (!hasText(baseUrl)) {
            return null;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBase + "/v1/convai/sip-trunk/outbound-call";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isEnabled(VoiceProviderConfiguration configuration, String key) {
        String value = configuration.settings().get(key);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private String redactSuffix(String value) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 4 ? "****" : "..." + trimmed.substring(trimmed.length() - 4);
    }

    private String redactSecret(String value) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 8
                ? trimmed.charAt(0) + "***"
                : trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private String fingerprint(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
}
