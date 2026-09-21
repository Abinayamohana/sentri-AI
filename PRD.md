# SentriAI — Product Requirements Document

**Version 1 (hackathon build) · Android · Branch `sentriAI-v2`**

SentriAI is an Android app for an elderly person living alone. It runs two independent paths to
the same caregiver alert: a passive listening pipeline that never leaves the device, and a live
voice companion built on the Agora Conversational AI Engine.

This document states what v1 is for and where its edges are. Implementation detail lives in
[AGORA.md](AGORA.md), [MODELS.md](MODELS.md) and [agora-backend/README.md](agora-backend/README.md).

---

## Problem

An older person living alone is exposed to three failures at once, and existing products address
at most one of them.

**Nobody notices.** A fall at 10am is found at 6pm when a relative rings and gets no answer. A
pendant alarm only works if it is worn and if the wearer is conscious and able to press it — the
two conditions least likely to hold in the emergency it exists for.

**Nobody asks.** A medicine card on the fridge is not a reminder. Doses drift, food rules are the
part people get wrong ("that one is *after* breakfast"), and no one finds out until a review
appointment. Adherence tools that ping a phone assume someone who reads notifications.

**Nobody talks.** The day is silent. That is its own slow harm, and it is the one nobody builds
for, because it does not produce an incident to report.

Underneath all three sits a fourth problem that blocks the obvious solutions: **always-on
monitoring means always-on surveillance.** A device that streams a home's audio to a server to
detect a fall is a device most families will not accept, and should not have to.

The specific gap v1 exists to close: **an SMS is not a person.** Between the moment something
happens and the moment help arrives there are minutes with nobody in them, and the only thing
that can fill them is a voice.

---

## Target User

### Primary — the elder ("Kamala", 78, lives alone)

Uses the phone every day, but not fluently. Reads slowly on a screen and hears better than she
reads. Takes four medicines at three different times, two of them tied to meals. Has fallen once
in the past year. Her son lives two hours away.

The design consequences are non-negotiable:

- **She configures nothing.** Not one setup step is hers. If a feature requires her to grant a
  permission, tune a setting, or remember a menu path, it will not be used.
- **Everything important is spoken**, not shown. Replies are one or two sentences, plain words,
  unhurried; never lists, never digits where a word is clearer ("half past eight", not "8:30").
- **She speaks slowly and pauses mid-sentence.** Turn detection tuned for a brisk answerer will
  talk over her, and she will stop using it.
- **She may be frightened, in pain, or unable to answer** at the exact moment the product matters
  most. Nothing may be conditional on her confirming anything.

### Secondary — the caregiver ("Ravi", 46, her son)

The buyer, the installer, and the person the alert reaches. He sets up the phone once, enters the
medicine schedule, and then wants to stop thinking about it. He needs to know the thing is still
working without asking his mother to test it, and when an alert arrives he needs enough context to
decide whether to call or to drive.

He tolerates false alarms far better than he tolerates silence. That asymmetry sets every
threshold in the product.

### Not a user

Care-home staff, clinicians, and anyone monitoring more than one person. v1 is one phone, one
elder, one emergency contact.

---

## Core Value Proposition

> **A phone that watches a room when nobody else can — and, when it matters, talks.**

Four claims, each of which a competing product breaks:

**1. It talks back.** Not a reminder chime and not a chatbot behind a text box — a full-duplex
voice call the elder can hold a conversation in, that knows her actual medicine schedule by name
and time, and that stays on the line through an emergency instead of hanging up after delivering
its message.

**2. Detection works with the network off.** The passive path — VAD → Whisper → Gemma classifier →
alert — runs entirely on the device. No audio, no transcript and no schedule leaves the phone on
that path. It keeps working with none of the Agora configuration present.

**3. The alert cannot be talked out of.** Every route to an emergency converges on one latch that
is set once and never cleared. Not by the agent smoothing things over, not by the person saying
"oh I'm fine really", not by any later summarisation. There is no code path that retracts it.

**4. It hears what a keyword list cannot.** "I feel dizzy" followed by twelve seconds of nothing is
someone who has stopped answering. The informative event is the *absence* of the next reply — the
one signal a phrase-matching monitor is structurally unable to see.

---

## Core Feature

The features v1 must ship complete. Each is built.

### CF-1 · Agora voice companion, three modes

Live full-duplex calls with an Agora Conversational AI agent that joins the elder's RTC channel as
uid 1001. `ConversationMode` selects the prompt, the greeting and the idle timeout.

| Mode | Purpose | Idle timeout |
|---|---|---|
| `DAILY_CHECKIN` | Agenda: wellbeing, each untaken dose by name with its food rule spoken aloud, meals, anything to pass to family. Then ends. | 120 s |
| `COMPANION` | No agenda. Family, childhood, old films, festivals, a bhajan. Listen more than speak. | 180 s |
| `EMERGENCY` | Stay with them until help arrives. Never ask whether help is really needed. Do not hang up. | 600 s |

Requirements:

- Greeting is sent as `greeting_message`, never improvised — the first thing a person in distress
  hears must be deterministic.
- Turn detection **Patient**, end-of-speech silence 1200–1500 ms, `enable_aivad` on.
- `AUDIO_SCENARIO_AI_CLIENT` audio profile; speakerphone by default (a phone on a table, not held
  to an ear).
- A live "companion is speaking" indicator, driven by volume indication at 300 ms.
- Failure at any setup step unwinds what succeeded before it — a channel joined but agentless is
  not a call.

### CF-2 · On-device prompt injection from the care schedule

`AgentPromptBuilder` renders the system prompt on the phone at join time, carrying the elder's
medicines, times, food rules and meal times from `CareScheduleStore`. The backend forwards it
verbatim and keeps no copy.

- The screen and the prompt are rendered by the same `CareBriefing` functions, so they cannot
  disagree about a dose.
- The agent has no tools and no database. It is instructed to use only the listed medicines, and to
  say it does not have it written down for anything else.
- **The agent must refuse to invent today.** No weather, no news, no date, no scores, not even a
  likely-sounding guess. Somebody may go out dressed for weather it invented.
- Emergency safety rules ship in **all three** prompts, not just the emergency one.

### CF-3 · Conversational escalation latch

`ConversationEscalationMonitor` watches the live transcript and raises at most one alert per call.
Five independent routes in:

1. A critical distress phrase from the elder (`ConversationDistress`, first-person anchored, with
   negation and third-person guards).
2. The agent emitting `[[SENTRI_ALERT:FALL|MEDICAL|HELP]]` — stripped before display, never spoken.
3. An elevated cue followed by 12 s of silence.
4. The on-device classifier, on anything the patterns did not match.
5. The SOS button pressed mid-call.

Requirements:

- Only **finalised** utterances escalate. ASR revises interim output, and "I fell" is a legitimate
  prefix of "I felt much better today".
- The latch is an atomic compare-and-set, per call, never cleared.
- Dispatch runs `NonCancellable` — the decision must survive the screen being closed a moment later.
- The RTC layer decides nothing; it emits transcript events and stops there.

### CF-4 · Passive on-device detection

Foreground `ListeningService` → VAD → Whisper tiny.en (ExecuTorch) → Gemma 3 1B classifier
(MediaPipe) → SMS.

- Two deterministic checks sit in front of the classifier: the safe word ("Mimi" ×3, matched
  tolerant of Whisper's i/e vowel confusions) and an explicit plea for help.
- The classifier is the only judge of unprompted speech; when its model is absent the app must
  surface a **degraded** state rather than appear armed.
- The mic has one owner. A call suspends passive listening and restores it afterwards, but only if
  it was running when the call began.

### CF-5 · Caregiver alert and trigger log

One dispatch path for every route in. SMS carries the emergency type, the exact phrase, and the
confidence. Every event is written to the local trigger log with the surrounding transcript,
whether or not the SMS succeeded — a failed send is still an event the caregiver must be able to see.

A clearly-labelled **test alert** uses the real dispatch path, because that is the only version of
the test that answers the question the caregiver is actually asking.

### CF-6 · Medicine and meal reminders

Alarm-scheduled full-screen overlay with a spoken prompt, snooze and stop, surviving reboot.
Includes a one-tap preview, because a feature whose whole job is to happen when nobody is looking
is otherwise impossible to verify.

### CF-7 · Model management

Runtime download of the three model assets from Hugging Face with SHA-256 verification and
resumable progress, so the APK does not carry ~600 MB of weights.

### CF-8 · Token and agent service

A stateless FastAPI service holding the two credentials that cannot ship in an APK: the App
Certificate (signs RTC tokens) and the Customer ID/Secret (starts billable agents). Three
endpoints, no database, no session table, no transcript. It refuses to boot if any required
credential is missing.

---

## Nice to Have

Wanted, not required for v1 to be worth shipping.

| | Why it matters | Why it waited |
|---|---|---|
| **Live data tools for the agent** — weather, headlines, scores via Agent Studio Actions or function calling | These are the three things people actually ask a companion for, and today the honest answer is "I don't know" | Needs a tool layer; the `NO_INVENTED_FACTS` prompt block is the stopgap and comes out the day this lands |
| **Scheduled automatic check-in calls** | Removes the last thing the elder has to initiate | Reminders are scheduled; calls are not. Needs CF-9 below to be safe |
| **Call survives the lock screen** — its own `microphone` foreground service | Locking the phone or switching apps currently ends a call | The call lives in a ViewModel; needs a service the way `ListeningService` has one |
| **RTC token renewal** on `onTokenPrivilegeWillExpire` | Long emergency calls could outlive an hour-long token | Every realistic companion call fits inside one token; this moves the cliff rather than removing it |
| **Caregiver app or web view** instead of SMS | Richer context, delivery receipts, history, two-way acknowledgement | SMS needs no install on the caregiver's side and works on any handset — the right v1 trade |
| **Multi-language** — `en-IN` ASR, Tamil/Hindi TTS and prompts | The target user often is not a native English speaker | The care briefing is deliberately English-only today so the model is not told something different when the phone's language changes |
| **Accelerometer fall detection** | Catches a fall that produces no speech at all | A whole second detection modality with its own false-positive profile |
| **Shared secret on `/agent/start`** | The endpoint starts billable agents | Supported server-side already (`SENTRI_API_KEY`); the app does not send the header yet |
| **Adherence trends for the caregiver** | Turns per-dose ticks into something a doctor can read | Needs history storage the app does not currently keep |

---

## Out of Scope (this version)

Deliberate exclusions. Each is a decision, not an omission.

- **Video.** The RTC dependency is `voice-sdk`, not `full-sdk`, specifically to keep video codecs,
  virtual background and face capture out of the APK.
- **Calling emergency services.** SentriAI alerts a named emergency contact. Auto-dialling 911/108
  on a model's judgement is a different product with a different liability surface.
- **Any medical function.** No diagnosis, no triage, no dose advice, no vitals. The agent is
  instructed to refer medical questions to a doctor or family, in every mode.
- **Multi-tenant anything.** One phone, one elder, one emergency contact. No accounts, no care-team
  roles, no facility dashboard.
- **Cloud storage of transcripts or schedules.** The backend is stateless by design. Audio during a
  call necessarily transits Agora; nothing is persisted by us on either path.
- **iOS, wearables, tablets, smart speakers.** Android phone only; arm64-v8a physical device
  (ExecuTorch native code).
- **HIPAA / clinical-grade compliance.** Not claimed, not audited, not certified.
- **Mid-call agent reconfiguration.** Emergency behaviour is in the prompt from the start in every
  mode rather than switched on when something happens, so Agora's `update` endpoint is never needed.
- **RTM / Signaling transcripts.** Transcripts stay on the RTC data stream; turning `enable_rtm` on
  would add the Signaling SDK for one callback.
- **Offline conversation.** With no network there is no companion call. The passive safety path is
  the offline guarantee; the conversation is not.
- **Background-initiated calls.** A call can only start while the app is foreground, because a
  microphone foreground service cannot be started from the background on API 34+.

---

## User Stories

Written from the two personas. Acceptance criteria are the testable part.

### Elder — companionship

**US-1** · *As Kamala, I want to talk to someone when the house is quiet, so the day is not silent.*
- One tap from the home screen starts a companion call; no sign-in, no mode picker jargon.
- The agent opens with a fixed greeting using her name, then lets her lead.
- It asks one question at a time and waits; it does not talk over her when she pauses.
- It never reads out markdown, bullets, emoji, or a digit where a word is clearer.

**US-2** · *As Kamala, I want it to admit when it does not know, so I do not act on something invented.*
- Asked for today's weather, news, date or a score, it says plainly it does not have it and offers
  something it can do.
- It never substitutes what the weather "usually" is at this time of year.

### Elder — care

**US-3** · *As Kamala, I want to be asked about my medicines by name, so I do not have to remember which is which.*
- The check-in call names each **untaken** dose and speaks its food rule aloud ("that one is after
  breakfast").
- If she says she has taken it, the agent believes her and moves on. If she forgot, it says kindly
  when to take it and moves on — no lecture, no repeat.
- It never discusses a medicine that is not on the schedule.

**US-4** · *As Kamala, I want a reminder I cannot miss, so a dose does not drift.*
- The reminder takes the full screen with a spoken prompt, not a notification in a tray.
- Snooze and stop are the only two controls, both large.
- Reminders survive a reboot.
- A dose ticked off before its reminder time is not then reminded about.

### Elder — emergency

**US-5** · *As Kamala, when I fall, I want help called without having to convince anything.*
- Distress in conversation escalates immediately — no confirmation question is ever asked.
- The agent says, in one short sentence, that it is getting help and staying with her.
- The alert marker is never spoken aloud, spelled, or mentioned.
- It keeps talking to her calmly and does not hang up.

**US-6** · *As Kamala, when I go quiet after saying something is wrong, I want that treated as the emergency it is.*
- An elevated cue arms a 12-second watchdog.
- The agent continuing to speak does **not** reset that watchdog — only she does.
- Speaking again cancels it; silence fires it.

**US-7** · *As Kamala, when I press SOS, I want the alert to go out before anything else happens.*
- SMS dispatches first, with no confirmation dialog.
- Only then does an emergency-mode call start, to keep her company.
- If Agora is unconfigured, offline, or mic permission is not granted, the call is skipped
  silently — a permission dialog in front of someone who has just pressed SOS is the worst possible
  moment to ask, and the alert is already out either way.

**US-8** · *As Kamala, I want to be heard even when nobody is on a call.*
- Ambient speech is transcribed and judged on the device; nothing is uploaded.
- Saying "Mimi" three times raises an alert deterministically, without the classifier.
- A direct call for help is caught deterministically, even as a single word.

### Caregiver

**US-9** · *As Ravi, I want an alert with enough context to decide, so I am not driving two hours on a guess.*
- The SMS names the emergency type, the exact phrase that triggered it, and a confidence.
- The log entry carries the surrounding transcript and which route detected it.

**US-10** · *As Ravi, I want to know the alert cannot be cancelled by the conversation that raised it.*
- Once raised, no later turn suppresses or retracts it.
- A second trigger in the same call does not produce a second SMS.
- This is pinned by `ConversationEscalationMonitorTest`, not by convention.

**US-11** · *As Ravi, I want to confirm it works without asking my mother to fall over.*
- A test alert sends through the real dispatch path and is clearly labelled as a test.
- The alert history shows every event, including failed sends and why they failed.

**US-12** · *As Ravi, I want to set the schedule once and not think about it.*
- Medicines, times, food rules and meal times are editable on the phone.
- Changing the schedule updates both the reminders and what the agent knows on the next call, with
  no separate sync step.

**US-13** · *As Ravi, I want to know when the safety net is not actually up.*
- Missing models surface as a **degraded** state naming what is missing — not silent, not shown as
  armed.
- Missing Agora configuration produces a specific message on the companion screen naming the unset
  property, not "call failed".

### Operator (setup)

**US-14** · *As the person deploying this, I want misconfiguration to fail loudly and locally.*
- The backend refuses to boot with any required credential missing.
- A call started with no `pipeline_id` is refused with a sentence naming the property to set —
  rather than surfacing an Agora vendor error three hops from the actual mistake.
- An agent started with an empty system prompt is refused, because it would answer the phone as a
  generic assistant with none of the safety rules: a failure that sounds like success.
- Agora's own error body is forwarded verbatim, never flattened to "upstream error".
