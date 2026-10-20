package com.yourcompany.surveyai.call.application.provider;

import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CallProviderRegistry {

    private final Map<CallProvider, VoiceExecutionProvider> providers;

    public CallProviderRegistry(List<VoiceExecutionProvider> providers) {
        this.providers = new EnumMap<>(CallProvider.class);
        for (VoiceExecutionProvider provider : providers) {
            this.providers.put(provider.getProvider(), provider);
        }
    }

    public VoiceExecutionProvider getRequiredProvider(CallProvider provider) {
        VoiceExecutionProvider resolved = providers.get(provider);
        if (resolved == null) {
            throw new NotFoundException("Sesli arama sağlayıcısı tanımlı değil: " + provider);
        }
        return resolved;
    }
}
