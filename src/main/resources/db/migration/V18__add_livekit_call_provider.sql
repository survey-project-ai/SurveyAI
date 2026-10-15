ALTER TABLE call_attempt
    DROP CONSTRAINT IF EXISTS chk_call_attempt_provider;

ALTER TABLE call_attempt
    ADD CONSTRAINT chk_call_attempt_provider
        CHECK (provider IN ('ELEVENLABS', 'MOCK', 'TWILIO', 'SIP', 'MANUAL', 'LIVEKIT'));
