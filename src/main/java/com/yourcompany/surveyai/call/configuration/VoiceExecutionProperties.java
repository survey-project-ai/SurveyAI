package com.yourcompany.surveyai.call.configuration;

import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "surveyai.calling")
public class VoiceExecutionProperties {

    private CallProvider activeProvider = CallProvider.MOCK;
    private ProviderProperties mock = new ProviderProperties();
    private ProviderProperties elevenlabs = new ProviderProperties();
    private ProviderProperties livekit = new ProviderProperties();

    public CallProvider getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(CallProvider activeProvider) {
        this.activeProvider = activeProvider;
    }

    public ProviderProperties getMock() {
        return mock;
    }

    public void setMock(ProviderProperties mock) {
        this.mock = mock;
    }

    public ProviderProperties getElevenlabs() {
        return elevenlabs;
    }

    public void setElevenlabs(ProviderProperties elevenlabs) {
        this.elevenlabs = elevenlabs;
    }

    public ProviderProperties getLivekit() {
        return livekit;
    }

    public void setLivekit(ProviderProperties livekit) {
        this.livekit = livekit;
    }

    public static class ProviderProperties {

        private boolean enabled;
        private VoiceProviderMode mode;
        private String apiKey;
        private String agentId;
        private String phoneNumberId;
        private String webhookSecret;
        private String baseUrl;
        private boolean sandboxMode;
        private Long webhookTimestampToleranceSeconds = 300L;
        private Map<String, String> settings = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public VoiceProviderMode getMode() {
            return mode;
        }

        public void setMode(VoiceProviderMode mode) {
            this.mode = mode;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getPhoneNumberId() {
            return phoneNumberId;
        }

        public void setPhoneNumberId(String phoneNumberId) {
            this.phoneNumberId = phoneNumberId;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isSandboxMode() {
            return sandboxMode;
        }

        public void setSandboxMode(boolean sandboxMode) {
            this.sandboxMode = sandboxMode;
        }

        public Long getWebhookTimestampToleranceSeconds() {
            return webhookTimestampToleranceSeconds;
        }

        public void setWebhookTimestampToleranceSeconds(Long webhookTimestampToleranceSeconds) {
            this.webhookTimestampToleranceSeconds = webhookTimestampToleranceSeconds;
        }

        public Map<String, String> getSettings() {
            return settings;
        }

        public void setSettings(Map<String, String> settings) {
            this.settings = settings;
        }
    }
}
