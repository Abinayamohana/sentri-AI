# SentriAI Agora backend

The service `agoraBackendUrl` points at. Three endpoints, ~300 lines, no database.

It exists because two things a conversational session needs cannot ship in an APK: the **App
Certificate** that signs RTC tokens, and the **Customer ID/Secret** that starts billable agents.
Both live here as environment variables. See [AGORA.md](../AGORA.md) for the full picture.

## Run it locally

```bash
cd agora-backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env          # then fill it in
set -a && source .env && set +a
.venv/bin/uvicorn main:app --host 0.0.0.0 --port 8080
```

It refuses to start if `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE`, `AGORA_CUSTOMER_ID` or
`AGORA_CUSTOMER_SECRET` is missing — a server that boots and then cannot mint tokens is worse
than one that will not boot.

### Pointing the app at it

The phone cannot reach `localhost`. Expose the port and put the public URL in `local.properties`:

```bash
ngrok http 127.0.0.1:8080          # note the explicit 127.0.0.1
```

**Give ngrok `127.0.0.1:8080`, not bare `8080`.** Bare `8080` makes it dial `localhost`, which
resolves to the IPv6 `::1` first, while `--host 0.0.0.0` binds IPv4 only. The result is a
confusing `ERR_NGROK_8012 — dial tcp [::1]:8080: connect: connection refused` while the server is
plainly running and answering on `127.0.0.1`. Matching the two by address avoids it.

The other working pairing is bare `ngrok http 8080` with `uvicorn --host ::`, which binds IPv6.
Be aware that on macOS that bind is IPv6-*only*: the tunnel works, but `http://127.0.0.1:8080`
and the LAN IP both stop responding, which is its own kind of confusing.

```properties
agoraAppId=<same App ID as the server's>
agoraPipelineId=<from the console's Embed Agent snippet>
agoraBackendUrl=https://<your-tunnel>.ngrok-free.app
```

Then rebuild — these are `BuildConfig` fields, so a Gradle sync is not enough.

Setting `agoraBackendUrl` switches the app off the debug direct-to-Agora path entirely
(`AgoraSessionRepository` prefers the backend whenever `hasBackend`), so `agoraCustomerId` and
`agoraCustomerSecret` in `local.properties` stop being consulted. You can leave or remove them.

## Deploying

Any host that terminates TLS. Cloud Run is the shortest path:

```bash
gcloud run deploy sentri-agora \
  --source . --region asia-south1 --allow-unauthenticated \
  --set-env-vars AGORA_APP_ID=...,AGORA_APP_CERTIFICATE=...,AGORA_CUSTOMER_ID=...,AGORA_CUSTOMER_SECRET=...
```

Prefer `--set-secrets` over `--set-env-vars` for the certificate and secret once you are past
first setup. Fly.io, Render and a plain container host all work the same way; the
[Dockerfile](Dockerfile) is standard.

`/health` is unauthenticated and returns `{"ok": true}` for the platform's health check.

## Endpoints

### `GET /rtc-token?channel={channel}&uid={uid}`

```json
{ "token": "006ab..." }
```

The token the **phone** joins with. The agent's is a separate one minted inside `/agent/start` —
a token is bound to a uid, so the two cannot share.

### `POST /agent/start`

Takes the app's flat body, reshapes it into Agora's `join` schema, forwards it with Basic auth:

```json
{ "channel": "sentri-1730000000000-4821", "uid": 481922, "agent_uid": 1001,
  "mode": "COMPANION", "system_prompt": "...", "greeting": "...",
  "idle_timeout": 120, "pipeline_id": "...", "llm_style": "openai" }
```

→ `{"agent_id": "1NT29X10YH..."}`

`agora_join_body()` is a direct port of `AgoraSessionRepository.agoraJoinBody()`, plus the one
thing the app cannot do: sign a token for the agent's own uid. **If you change one, change the
other** — the debug direct path and this one should produce the same call.

An Agora error comes back as a 502 with Agora's own body in the `detail`, not flattened to
"upstream error". The app prints the first 500 characters on the companion screen, and Agora's
400 names the misconfigured vendor or the malformed field.

### `POST /agent/stop`

`{"agent_id": "..."}` → forwards to Agora's `leave`. Best effort; the app logs and swallows a
failure here because the call is already over and `idle_timeout` reaps a stranded agent anyway.

## Authentication

**`/agent/start` is unauthenticated by default and starts billable Agora agents.** The app does
not send credentials, so this is the only configuration that currently works, and the service
logs a warning at boot to keep it from being forgotten.

`SENTRI_API_KEY` implements the other half: set it and every endpoint requires
`Authorization: Bearer <key>`. Turning it on needs a matching change in
`AgoraSessionRepository`'s `get()`/`post()` to send the header — until that lands, setting the
variable will break calls.

Until then, the practical mitigations are an obscure hostname, Agora spend limits, and not
publishing the URL.

## Vendor configuration: set `agoraPipelineId`

With `agoraPipelineId` set, no `asr`/`tts`/`llm` vendor block is sent and the published Agent
Studio agent supplies all three. Without it, `MANAGED_*` in `.env` must name vendors your Agora
project is provisioned for — and on this project, none of them worked. Probed 2026-08-03 against
`join` with `credential_mode: "managed"`:

| `asr.vendor` | result |
|---|---|
| `ares`, `microsoft`, `tencent`, `volcano`, `openai` | `not available for the current SKU` |
| `deepgram` | passes the vendor check, then demands `params.url` — a Deepgram endpoint |

So treat the inline path as unusable here and publish a Studio agent instead. That is the better
arrangement anyway: the voice and the model become a console edit rather than an app release.

Because that path cannot succeed here, `/agent/start` **refuses outright** when no `pipeline_id`
arrives, with a message naming `agoraPipelineId`. Attempting the call instead would bury the real
mistake — an unset Gradle property — under an Agora vendor error three hops away from it. Set
`ALLOW_INLINE_VENDORS=true` to try it anyway on an account that is provisioned for it.

## Things that will bite

- **`agoraLlmStyle` must match the agent's LLM.** Gemini has no `system` role. Send the OpenAI
  shape to Gemini and nothing errors — the agent starts, answers, and behaves like a generic
  assistant, because the prompt carrying the medicine schedule was silently dropped. The app
  sends its setting as `llm_style`; both ends must agree.
- **`FAILURE_MESSAGE` and `AGENT_UID` are duplicated on the device** in `AgentPromptBuilder` and
  `AgoraConfig`. A changed `AGENT_UID` means the client never recognises the agent joining.
- **Tokens last an hour and the app cannot renew them.** `onTokenPrivilegeWillExpire` logs and
  does nothing (`TODO(agora-token-renewal)`). Raising `TOKEN_TTL_SECONDS` moves the cliff rather
  than removing it.
- **The system prompt is never stored here.** It arrives, goes to Agora, and is forgotten. It
  contains the person's medicine schedule; keep it that way.
- **IDE import errors** mean the editor is on the system interpreter — point it at `.venv`.
