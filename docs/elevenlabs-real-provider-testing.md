# ElevenLabs Real Provider Testing

## Required environment variables

- `ELEVENLABS_API_KEY`
- `ELEVENLABS_AGENT_ID`
- `ELEVENLABS_PHONE_NUMBER_ID`
- `ELEVENLABS_WEBHOOK_SECRET`
- `PUBLIC_WEBHOOK_BASE_URL`

Optional:

- `ELEVENLABS_SANDBOX_MODE=true|false`
- `ELEVENLABS_CALL_RECORDING_ENABLED=true|false`
- `ELEVENLABS_TOOL_API_SECRET`
- `ELEVENLABS_AGENT_PROMPT_OVERRIDE_ENABLED=true|false`
- `ELEVENLABS_AGENT_LLM` (for example `claude-haiku-5-5`): sent with the prompt override, so the agent's Security tab must allow the LLM override. Empty keeps the agent's own model.
- `ELEVENLABS_AGENT_FIRST_MESSAGE_OVERRIDE_ENABLED=true|false`
- `ELEVENLABS_AGENT_LANGUAGE_OVERRIDE_ENABLED=true|false`

## Provider mode

- Sandbox mode: `surveyai.calling.active-provider=ELEVENLABS`, `surveyai.calling.elevenlabs.enabled=true`, `ELEVENLABS_SANDBOX_MODE=true`
  This exercises dispatch, provider correlation, webhook plumbing, and inspection endpoints without placing a real call.
- Live mode: set `ELEVENLABS_SANDBOX_MODE=false`
  This places a real outbound call through ElevenLabs/Twilio.

## Webhook URL

Configure ElevenLabs post-call webhooks to POST to:

`{PUBLIC_WEBHOOK_BASE_URL}/api/v1/provider-webhooks/ELEVENLABS`

The server will log the expected URL at startup. For local testing, expose the app with ngrok or another public tunnel and set `PUBLIC_WEBHOOK_BASE_URL` to that public base URL.

## Important live-call assumptions

- Live outbound calls use the stored agent configuration referenced by `ELEVENLABS_AGENT_ID`.
- Preview-only or unsaved dashboard changes do not affect live outbound calls.
- Code-side prompt override is enabled by default unless `ELEVENLABS_AGENT_PROMPT_OVERRIDE_ENABLED=false` is set explicitly. First-message and language overrides are also controlled by their corresponding `ELEVENLABS_AGENT_*_OVERRIDE_ENABLED` flags.
- Even when the app sends those overrides, the same fields must also be explicitly allowed in the agent Security tab or ElevenLabs may reject the runtime override during conversation startup.
- For Twilio-backed phone calls, configure the agent for `mu-law 8000 Hz` input and output. A telephony audio mismatch can cause the call to connect but fail before the conversational layer stabilizes.

## One-contact E2E test flow

1. Start the app and confirm startup diagnostics log the active provider, sandbox/live mode, and expected webhook URL.
2. Create or reuse a published survey with a minimal question set.
3. Create an operation for that survey and import exactly one contact with a real reachable phone number.
4. Verify readiness, then start the operation.
5. Open the call-job detail endpoint and the provider execution debug endpoint:
   - `GET /api/v1/operations/{operationId}/jobs?companyId={companyId}`
   - `GET /api/v1/operations/{operationId}/jobs/{callJobId}?companyId={companyId}`
   - `GET /api/v1/companies/{companyId}/provider-executions?operationId={operationId}`
6. Confirm the dispatch stage produced:
   - `provider=ELEVENLABS`
   - a `providerCallId`
   - `dispatch.accepted` or `dispatch.failed`
   - `status.snapshot` shortly after dispatch in live mode when ElevenLabs status fetch succeeds
7. Wait for the post-call webhook and confirm a webhook event shows:
   - event type such as `post_call_transcription` or `call_initiation_failure`
   - for matched webhooks, populated `callJobId` and `callAttemptId`
   - outcome `ACCEPTED`, `IGNORED`, or `UNMATCHED`
8. Confirm result persistence:
   - `SurveyResponse` exists for the `callAttemptId`
   - `answerCount` is populated in provider execution events
   - `unmappedFieldCount` is `0` for a clean mapping or non-zero for partial mapping
   - transcript availability is visible in both call attempt detail and provider execution events
9. Check analytics after the webhook lands to confirm the response was included.

## What to inspect when something breaks

- Dispatch failure:
  Look for `dispatch.failed` in `/provider-executions`, then check `call_job.last_error_message` and `call_attempt.failure_reason`.
- Missing webhook:
  Confirm ElevenLabs can reach the public webhook URL and that the server logs `Provider webhook received`.
- No tool hits in a real live call:
  If `/api/v1/provider-tools/...` is never reached, the call likely never entered the conversational tool layer. Check `status.snapshot`, the raw provider payload, the published agent configuration for `ELEVENLABS_AGENT_ID`, telephony audio format, and whether runtime overrides are being rejected by agent security.
- Call answered then ends immediately:
  Treat this as a pre-tool failure until proven otherwise. First inspect `dispatch.accepted`, `status.snapshot` / `status.snapshot.failed`, and any terminal webhook reason such as Twilio `call_status`, `sip_status`, or ElevenLabs `termination_reason`.
- Rejected webhook:
  Check server logs for `Provider webhook rejected`, then verify signature secret / timestamp drift.
- Duplicate webhook:
  Look for `IGNORED` webhook events with a stale or duplicate message.
- Partial mapping:
  Look for a `RESULT` event with outcome `PARTIAL`, then inspect `unmappedFieldCount`, `SurveyResponse.transcriptJson`, and the raw payload captured in execution events.
