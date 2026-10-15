package com.yourcompany.surveyai.call.configuration;

import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.common.exception.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class VoiceProviderConfigurationResolver {

    private final VoiceExecutionProperties properties;

    public VoiceProviderConfigurationResolver(VoiceExecutionProperties properties) {
        this.properties = properties;
    }

    public CallProvider getActiveProvider() {
        return properties.getActiveProvider();
    }

    public VoiceProviderConfiguration getActiveConfiguration() {
        return getConfiguration(properties.getActiveProvider());
    }

    public VoiceProviderConfiguration getConfiguration(CallProvider provider) {
        VoiceExecutionProperties.ProviderProperties config = switch (provider) {
            case MOCK -> properties.getMock();
            case ELEVENLABS -> properties.getElevenlabs();
            case LIVEKIT -> properties.getLivekit();
            default -> throw new ValidationException("Provider configuration is not modeled yet for: " + provider);
        };

        return new VoiceProviderConfiguration(
                provider,
                config.isEnabled(),
                resolveMode(config),
                trimToNull(config.getApiKey()),
                trimToNull(config.getAgentId()),
                trimToNull(config.getPhoneNumberId()),
                trimToNull(config.getWebhookSecret()),
                trimToNull(config.getBaseUrl()),
                config.isSandboxMode(),
                config.getWebhookTimestampToleranceSeconds() == null ? 300L : config.getWebhookTimestampToleranceSeconds(),
                trimSettings(config.getSettings())
        );
    }

    private VoiceProviderMode resolveMode(VoiceExecutionProperties.ProviderProperties config) {
        if (config.getMode() != null) {
            return config.getMode();
        }
        return config.isSandboxMode() ? VoiceProviderMode.MOCK : VoiceProviderMode.LIVE;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Map<String, String> trimSettings(Map<String, String> settings) {
        Map<String, String> trimmed = new LinkedHashMap<>();
        if (settings == null || settings.isEmpty()) {
            return trimmed;
        }
        settings.forEach((key, value) -> trimmed.put(key, trimToNull(value)));
        return trimmed;
    }
}
