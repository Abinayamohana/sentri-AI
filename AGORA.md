# Agora Conversational AI — setup

The Daily Companion and Emergency screens place live voice calls with an
[Agora Conversational AI Engine](https://docs.agora.io/en/conversational-ai/overview/product-overview)
agent. This is the second path through the app; the passive
`mic → VAD → Whisper → EmergencyDetector → SMS` pipeline is untouched and keeps
working with none of this configured.

## What you need

| Setting | Where it comes from | Required |
|---|---|---|
| `agoraAppId` | Agora Console → your project → App ID | yes |
| `agoraPipelineId` | Console → Agents → your agent → Embed Agent | recommended |
| `agoraLlmStyle` | `gemini` if the agent's LLM is Gemini/Vertex AI, else unset | see below |
| `agoraBackendUrl` | a service you run — [`agora-backend/`](agora-backend/) implements it | yes for production |
| `agoraCustomerId` | Agora Console → RESTful API → Customer ID | debug only |
| `agoraCustomerSecret` | Agora Console → RESTful API → Customer Secret | debug only |

Put them in `local.properties` (gitignored) or `gradle.properties`:

```properties
agoraAppId=aabbccdd11223344aabbccdd11223344
agoraPipelineId=<from the Embed Agent snippet>
agoraBackendUrl=https://your-service.example.com
```

## Creating the Agent Studio agent

Console → **Agents** → **Create Agent**. Configure it as the *voice*; the app supplies the
*words*.

**Prompt tab.** Leave the System Prompt as a one-line placeholder — the app overrides it on
every call, because the real prompt carries today's medicine schedule and differs per mode.
Same for Greeting Message. Set **Failure Message** to `I'm sorry, I didn't catch that. Give me a
moment.` to match `AgentPromptBuilder.failureMessage()`.

**Models tab.** ASR language `en-US` (or `en-IN`). Pick any LLM your account is provisioned for —
a small fast one is right here, since replies are one or two sentences. For TTS pick a slow,
warm voice and *listen to it before publishing*: this is the voice an elderly person hears every
day, and it is the single setting with the most effect on whether the feature gets used.

**Advanced tab.** Turn detection **Patient** — the default is tuned for people who answer
briskly. Raise End-of-Speech **Silence Duration** to around 1200–1500 ms so a slow speaker is not
cut off mid-sentence. Max History `32`.

Then **Publish**, open **Embed Agent**, and copy the `pipeline_id` into `agoraPipelineId`.

**What the app overrides at join time**, regardless of what the console says: `system_messages`,
`greeting_message`, `failure_message`, `max_history`, `idle_timeout`, and the channel/uid
properties. Everything else — vendors, voice, turn detection — comes from the published agent, so
changing the voice or the model is a console edit, not an app release.

Without `agoraPipelineId` the app falls back to sending its own vendor block; see
`TODO(agora-vendors)` in `AgoraSessionRepository`.

They reach the app as `BuildConfig` fields; `AgoraConfig` reads them and
`AgoraConfig.missingRequirement()` produces the message the Daily Companion screen shows when
something is unset.

## Why a backend is needed

Two things a session needs cannot go in an APK:

- **The RTC token** is signed with the project's **App Certificate**. Shipping the certificate
  lets anyone mint tokens on your account.
- **Starting the agent** authenticates with the **Customer ID/Secret**, which can start billable
  agents and are not scoped to a channel.

So the app calls a service you own. `agoraCustomerId`/`agoraCustomerSecret` exist only so the
flow can be exercised before that service is written: they are compiled into **debug builds
only**, and `AgoraSessionRepository` additionally refuses to use them unless `BuildConfig.DEBUG`.

## The three endpoints to implement

Consumed by `AgoraSessionRepository`. All JSON, all small.

A working implementation lives in [`agora-backend/`](agora-backend/) — FastAPI, one file, with a
Dockerfile. `agora_join_body()` there is a port of `AgoraSessionRepository.agoraJoinBody()`;
change one and change the other. The rest of this section is the contract it satisfies, kept here
for anyone reimplementing it in another language.

### `GET {base}/rtc-token?channel={channel}&uid={uid}`

```json
{ "token": "007eJxTYBBZ…" }
```

Build it with `agora-token` (Node), `agora-token-builder` (Python), or the Go/Java equivalents,
using your App Certificate. Role `publisher`, an hour of life is plenty — see the
`TODO(agora-token-renewal)` in `AgoraConversationManager` for what happens past that.

### `POST {base}/agent/start`

```json
{
  "channel": "sentri-1730000000000-4821",
  "uid": 481922,
  "agent_uid": 1001,
  "mode": "COMPANION",
  "system_prompt": "You are Sentri, a warm, calm voice companion for …",
  "greeting": "Hello Kamala. It's Sentri, just checking in on you…",
  "idle_timeout": 120
}
```

Respond with `{"agent_id": "1NT29X10YH…"}`.

Your handler forwards this to Agora, adding the Customer ID/Secret and your LLM/TTS vendor
configuration:

```
POST https://api.agora.io/api/conversational-ai-agent/v2/projects/{appId}/join
Authorization: Basic base64(customerId:customerSecret)
```

```json
{
  "name": "sentri-<channel>",
  "pipeline_id": "<agoraPipelineId>",
  "properties": {
    "channel": "<channel>",
    "token": "<the rtc token for agent_uid>",
    "agent_rtc_uid": "1001",
    "remote_rtc_uids": ["<uid>"],
    "idle_timeout": 120,
    "llm": {
      "system_messages": [{ "role": "system", "content": "<system_prompt>" }],
      "greeting_message": "<greeting>",
      "failure_message": "I'm sorry, I didn't catch that. Give me a moment.",
      "max_history": 32
    },
    "advanced_features": { "enable_aivad": true, "enable_rtm": false }
  }
}
```

`pipeline_id` is the published Studio agent and acts as the base configuration; anything under
`properties` overrides the corresponding saved setting. With no `pipeline_id`, the app instead
sends full `asr` / `llm` / `tts` vendor blocks in `credential_mode: "managed"`.

`AgoraSessionRepository.agoraJoinBody()` builds exactly this for the debug path — copy it.

The **system prompt is built on the device**, not on your server. It contains the person's
medicine schedule, which lives in `CareScheduleStore` on the phone and has no reason to be
replicated anywhere else.

### `POST {base}/agent/stop`

```json
{ "agent_id": "1NT29X10YH…" }
```

Forwards to
`POST https://api.agora.io/api/conversational-ai-agent/v2/projects/{appId}/agents/{agentId}/leave`.

## Vendor configuration

With `agoraPipelineId` set, vendors come from the published agent and nothing in the app needs to
name them.

Without it, `MANAGED_LLM_VENDOR` / `MANAGED_TTS_VENDOR` / `MANAGED_LLM_MODEL` in
`AgoraSessionRepository` must match what your project is provisioned for — see the
`TODO(agora-vendors)` there. A vendor the account cannot use comes back as a 400 with the reason
in the body, which the repository logs verbatim.

## If you pick Gemini as the LLM

Set `agoraLlmStyle=gemini`. Gemini has no `system` role, so the prompt goes in a different shape
and Agora needs `style: "gemini"` to know which dialect it is talking:

```jsonc
// openai (default)
{ "role": "system", "content": "…" }
// gemini / vertex ai
{ "role": "user", "parts": [{ "text": "…" }] }
```

Getting this wrong **does not produce an error**. The agent starts, answers, and behaves like a
generic assistant — because the prompt carrying the medicine schedule and every safety rule was
silently dropped. If a call sounds oddly generic and the agent knows nothing about the schedule,
this is the first thing to check.

## Transcripts

`enable_rtm` is **false**, so transcripts arrive over the RTC data stream and surface in
`IRtcEngineEventHandler.onStreamMessage`. `ConversationMessageParser` reassembles them:

```
<messageId>|<partIndex>|<totalParts>|<base64 chunk>       partIndex is 1-based
```

decoding to

```json
{"object": "assistant.transcription", "text": "Hello. How are you?",
 "turn_id": 1, "turn_status": 1, "stream_id": 0, "words": [...]}
```

Three properties of this format were verified against a live session, and each one bites if
assumed away:

- **`object` names the kind.** Only `assistant.transcription` and `user.transcription` carry
  conversation text. `message.state` also arrives on this stream (`idle`, `listening`,
  `thinking`, `speaking`, `silent`) and has no `text`. `stream_id == 0` means the agent, and is
  the fallback when `object` is absent.
- **There is no `is_final`.** Completion is `turn_status`: `0` still speaking, `1` ended, `2`
  interrupted by the person. A missing flag read as "complete" makes every update a finished
  utterance.
- **`text` is the whole turn so far**, resent on each update — not the newest fragment. Consumers
  replace what they hold for that `turn_id`; appending stacks prefixes of one sentence
  (`"Hello,"`, `"Hello, aa."`, `"Hello, aa. It's Sentri."` …).

`text` is assembled from the TTS word timings, which carry no leading whitespace, so sentences
arrive glued: `"Hello,aa.It's Sentri."`. The parser puts the spaces back.

Turning `enable_rtm` on moves transcripts to the Signaling channel and would require the Agora
RTM SDK plus Agora's Conversational AI toolkit — which ships as source to vendor in, not as a
Maven artifact. The data-stream path needs neither.

## Emergency escalation

A conversation reaches the caregiver through the **existing** pipeline. Nothing about the SMS or
the trigger log is duplicated for calls.

```
AgoraConversationManager ──TranscriptEvent──▶ ConversationEscalationMonitor
                                                       │ onEscalate
                                                       ▼
                                          EmergencyPipeline.dispatch ──▶ SMS + trigger log
```

Four ways in, one latch:

1. `ConversationDistress` matches a critical phrase in what the person said.
2. The agent emits `[[SENTRI_ALERT:FALL|MEDICAL|HELP]]`, which the prompt instructs it to append
   and never to speak. Stripped before display.
3. An **elevated** cue followed by 12 seconds of silence — see
   `ConversationEscalationMonitor.SILENCE_AFTER_DISTRESS_MS`.
4. `EmergencyDetector`'s classifier, on anything the patterns did not match.

`EmergencyPipeline.dispatch` is stage 2 alone: it does **not** re-run detection, because the only
new outcome that could produce is a NO that discards an emergency already established. Nothing —
not the agent smoothing things over, not the person walking it back, not a later summarisation —
can clear the latch or suppress the alert. `ConversationEscalationMonitorTest` pins that down.

## Microphone

`ListeningService` and Agora both want the mic and only one can have it. `CompanionCallViewModel`
stops passive listening for the duration of a call and restarts it afterwards, but only if it was
running when the call began.

## Known limitations

- **Calls are tied to the screen.** The call lives in a ViewModel, so locking the phone or
  switching apps ends it. Surviving that needs a `microphone`-type foreground service of its own,
  the way `ListeningService` has one.
- **No token renewal.** `onTokenPrivilegeWillExpire` logs and does nothing. Issue tokens with an
  hour of life and every realistic companion call fits inside one.
- **The agent cannot be reconfigured mid-call.** Emergency behaviour is in the system prompt from
  the start in every mode rather than switched on when something happens, so the agent behaves
  correctly during a fall on a weather chat without needing Agora's `update` endpoint.
