"""
The token/agent service the SentriAI app talks to when `agoraBackendUrl` is set.

Three endpoints, consumed by `AgoraSessionRepository` on the device. The contract is pinned
there and in AGORA.md; this file implements it and nothing more.

    GET  /rtc-token?channel={channel}&uid={uid}  -> {"token": "..."}
    POST /agent/start                            -> {"agent_id": "..."}
    POST /agent/stop                             -> {"stopped": true}

### Why this service exists at all

Two of the things a conversational session needs cannot legitimately ship in an APK:

- The **RTC token** is signed with the project's App Certificate. Shipping the certificate
  would let anyone mint tokens on the account.
- **Starting the agent** authenticates with the Agora RESTful Customer ID/Secret, which can
  start billable agents and is not scoped to a channel.

Both live here instead, as environment variables, and never leave the server.

### What is deliberately *not* here

The **system prompt is built on the device** and passed through verbatim. It carries the
person's medicine schedule, which lives in `CareScheduleStore` on the phone and has no reason
to be replicated in a server this service could otherwise be stateless without. This service
keeps no database, no session table and no transcript.
"""

from __future__ import annotations

import base64
import logging
import os
import re
import time
from contextlib import asynccontextmanager
from typing import Any

import httpx
from agora_token_builder import RtcTokenBuilder
from dotenv import load_dotenv
from fastapi import Body, FastAPI, Header, HTTPException, Query
from fastapi.responses import JSONResponse

# So a local `.env` works without sourcing it first. Real environment variables win, which is
# what deployed hosts set — nothing here overrides Cloud Run's or Fly's configuration.
load_dotenv(override=False)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("sentri-agora")

# --- configuration ----------------------------------------------------------------------

APP_ID = os.environ.get("AGORA_APP_ID", "").strip()
APP_CERTIFICATE = os.environ.get("AGORA_APP_CERTIFICATE", "").strip()
CUSTOMER_ID = os.environ.get("AGORA_CUSTOMER_ID", "").strip()
CUSTOMER_SECRET = os.environ.get("AGORA_CUSTOMER_SECRET", "").strip()

# An hour. Every realistic companion call fits inside one, which matters because the app has no
# token renewal — see TODO(agora-token-renewal) in AgoraConversationManager. Raising this is not
# a substitute for implementing renewal; it just moves the cliff.
TOKEN_TTL_SECONDS = int(os.environ.get("TOKEN_TTL_SECONDS", "3600"))

# Optional shared secret. Off by default because the app does not send one yet — see the
# "Authentication" section of the README before turning it on.
API_KEY = os.environ.get("SENTRI_API_KEY", "").strip()

AGORA_API_BASE = "https://api.agora.io/api/conversational-ai-agent/v2/projects"

# Role_Publisher in the Agora token builder. Hard-coded rather than imported because the
# constant has been spelled three different ways across versions of the package; the value has
# not changed.
ROLE_PUBLISHER = 1

# The uid the agent joins as. Must match AgoraConfig.AGENT_UID — the client recognises the agent
# by this number, so a mismatch means the app never sees it arrive.
AGENT_UID = 1001

MAX_HISTORY = 32

# Must match AgentPromptBuilder.failureMessage(). Also set it on the Studio agent's Prompt tab.
FAILURE_MESSAGE = "I'm sorry, I didn't catch that. Give me a moment."

# Whether to attempt the inline-vendor path when the app sends no pipeline_id.
#
# Off, because on this project it cannot succeed: every managed ASR vendor is rejected for the
# account's SKU (see the README table). Attempting it anyway buries "you forgot to set
# agoraPipelineId" under an Agora vendor error three hops away from the actual mistake, which is
# how an afternoon goes missing. Turn it on only if your account is provisioned for the
# MANAGED_* vendors below.
ALLOW_INLINE_VENDORS = os.environ.get("ALLOW_INLINE_VENDORS", "").lower() in ("1", "true", "yes")

# Only consulted when the app sends no pipeline_id, i.e. when there is no published Agent Studio
# agent to take the vendors from. These must match what your Agora project is provisioned for; a
# vendor the account cannot use comes back as a 400 whose body names it.
MANAGED_ASR_LANGUAGE = os.environ.get("MANAGED_ASR_LANGUAGE", "en-US")
# `ares` is Agora's own ASR. Required by the join schema whenever an `asr` block is sent at all —
# omitting it returns `Invalid value at properties.asr.vendor: required field is missing`.
MANAGED_ASR_VENDOR = os.environ.get("MANAGED_ASR_VENDOR", "ares")
MANAGED_LLM_VENDOR = os.environ.get("MANAGED_LLM_VENDOR", "openai")
MANAGED_LLM_MODEL = os.environ.get("MANAGED_LLM_MODEL", "gpt-4o-mini")
MANAGED_TTS_VENDOR = os.environ.get("MANAGED_TTS_VENDOR", "microsoft")

# Agora's own restriction on channel names, applied here so a malformed one fails with a clear
# 400 rather than as an opaque rejection two hops away.
CHANNEL_RE = re.compile(r"^[A-Za-z0-9 !#$%&()+\-:;<=.>?@\[\]^_{}|~,]{1,64}$")

def check_configuration() -> None:
    """
    Fail loudly at boot rather than on the first call.

    The App Certificate is required, not optional: once `agoraBackendUrl` is set the app stops
    tolerating a missing token and treats an absent `token` field as an error. A server that
    boots and then cannot mint tokens is worse than one that refuses to boot.
    """
    missing = [
        name
        for name, value in (
            ("AGORA_APP_ID", APP_ID),
            ("AGORA_APP_CERTIFICATE", APP_CERTIFICATE),
            ("AGORA_CUSTOMER_ID", CUSTOMER_ID),
            ("AGORA_CUSTOMER_SECRET", CUSTOMER_SECRET),
        )
        if not value
    ]
    if missing:
        raise RuntimeError(
            "Missing required environment variables: "
            + ", ".join(missing)
            + ". Copy .env.example to .env and fill it in."
        )
    if not API_KEY:
        log.warning(
            "SENTRI_API_KEY is unset: /agent/start is open to anyone who can reach this host, "
            "and it starts billable Agora agents. See the README."
        )


@asynccontextmanager
async def lifespan(_: FastAPI):
    check_configuration()
    yield


app = FastAPI(
    title="SentriAI Agora backend",
    lifespan=lifespan,
    # No interactive docs. This service has three endpoints documented in the README and one of
    # them starts billable agents; there is nothing to gain from advertising the schema.
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


def require_api_key(authorization: str | None) -> None:
    """No-op unless SENTRI_API_KEY is set. See the README's Authentication section."""
    if not API_KEY:
        return
    expected = f"Bearer {API_KEY}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="Bad or missing Authorization header")


def basic_auth_header() -> str:
    raw = f"{CUSTOMER_ID}:{CUSTOMER_SECRET}".encode("utf-8")
    return "Basic " + base64.b64encode(raw).decode("ascii")


def build_token(channel: str, uid: int) -> str:
    expires_at = int(time.time()) + TOKEN_TTL_SECONDS
    return RtcTokenBuilder.buildTokenWithUid(
        APP_ID, APP_CERTIFICATE, channel, uid, ROLE_PUBLISHER, expires_at
    )


def validate_channel(channel: str) -> str:
    if not CHANNEL_RE.match(channel or ""):
        raise HTTPException(status_code=400, detail=f"Invalid channel name: {channel!r}")
    return channel


# --- endpoints --------------------------------------------------------------------------


@app.get("/health")
def health() -> dict[str, Any]:
    """For the platform's health check. Says nothing secret — no app id, no vendor names."""
    return {"ok": True}


@app.get("/rtc-token")
def rtc_token(
    channel: str = Query(...),
    uid: int = Query(...),
    authorization: str | None = Header(default=None),
) -> dict[str, str]:
    """
    The token the *phone* joins with.

    The agent's token is a separate one, minted inside /agent/start for AGENT_UID — a token is
    bound to a uid, so the two cannot share.
    """
    require_api_key(authorization)
    validate_channel(channel)
    log.info("token for channel=%s uid=%s", channel, uid)
    return {"token": build_token(channel, uid)}


@app.post("/agent/start")
async def agent_start(
    payload: dict[str, Any] = Body(...),
    authorization: str | None = Header(default=None),
) -> dict[str, str]:
    """
    Reshapes the app's flat body into Agora's `join` schema and forwards it.

    The app has already joined the channel by the time this is called, so the agent's greeting
    is spoken to someone who is there to hear it.
    """
    require_api_key(authorization)

    channel = validate_channel(str(payload.get("channel", "")))
    uid = int(payload.get("uid", 0))
    agent_uid = int(payload.get("agent_uid", AGENT_UID))
    mode = str(payload.get("mode", "UNKNOWN"))
    system_prompt = str(payload.get("system_prompt", ""))
    greeting = str(payload.get("greeting", ""))
    idle_timeout = int(payload.get("idle_timeout", 120))
    pipeline_id = str(payload.get("pipeline_id", "")).strip()
    llm_style = str(payload.get("llm_style", "openai")).strip().lower()

    if not pipeline_id and not ALLOW_INLINE_VENDORS:
        # Named plainly, because the app prints this sentence on the companion screen and the
        # alternative is an Agora vendor error that says nothing about the property that is unset.
        raise HTTPException(
            status_code=400,
            detail=(
                "No pipeline_id was sent. Publish an Agent Studio agent, then set "
                "`agoraPipelineId=<id>` in local.properties and rebuild. (Server-side override: "
                "ALLOW_INLINE_VENDORS=true, which this project's Agora SKU rejects anyway.)"
            ),
        )

    if not system_prompt:
        # Not pedantry. An agent started with an empty prompt answers the phone and behaves like
        # a generic assistant, with none of the medicine schedule and none of the safety rules —
        # a failure that sounds like success. Refuse instead.
        raise HTTPException(status_code=400, detail="system_prompt is required")

    body = agora_join_body(
        channel=channel,
        uid=uid,
        agent_uid=agent_uid,
        system_prompt=system_prompt,
        greeting=greeting,
        idle_timeout=idle_timeout,
        pipeline_id=pipeline_id,
        llm_style=llm_style,
    )

    url = f"{AGORA_API_BASE}/{APP_ID}/join"
    log.info("starting %s agent on channel=%s uid=%s", mode, channel, uid)

    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(
            url,
            json=body,
            headers={"Authorization": basic_auth_header(), "Content-Type": "application/json"},
        )

    if response.status_code >= 300:
        # Forwarded verbatim, not flattened to "upstream error". Agora's 400 names the
        # misconfigured vendor or the malformed field, the app surfaces the first 500 characters
        # of this body on the companion screen, and discarding it turns a five-second fix into an
        # afternoon.
        log.error("agora join failed %s: %s", response.status_code, response.text[:1000])
        raise HTTPException(
            status_code=502,
            detail=f"Agora join returned {response.status_code}: {response.text[:800]}",
        )

    agent_id = extract_agent_id(response.json())
    if not agent_id:
        raise HTTPException(
            status_code=502, detail=f"Agora join returned no agent id: {response.text[:400]}"
        )

    log.info("agent %s running on channel=%s", agent_id, channel)
    return {"agent_id": agent_id}


@app.post("/agent/stop")
async def agent_stop(
    payload: dict[str, Any] = Body(...),
    authorization: str | None = Header(default=None),
) -> JSONResponse:
    """
    Best effort, mirroring the client.

    The app logs and swallows a failure here — the call is already over from the person's point
    of view and Agora's `idle_timeout` reaps an unreferenced agent on its own. This still reports
    the truth so the failure is visible in logs rather than only inferable from the bill.
    """
    require_api_key(authorization)

    agent_id = str(payload.get("agent_id", "")).strip()
    if not agent_id:
        raise HTTPException(status_code=400, detail="agent_id is required")

    url = f"{AGORA_API_BASE}/{APP_ID}/agents/{agent_id}/leave"
    async with httpx.AsyncClient(timeout=15) as client:
        response = await client.post(
            url, json={}, headers={"Authorization": basic_auth_header()}
        )

    if response.status_code >= 300:
        log.warning("stopping agent %s failed %s: %s", agent_id, response.status_code, response.text[:400])
        return JSONResponse(
            status_code=502,
            content={"stopped": False, "detail": response.text[:400]},
        )

    log.info("agent %s stopped", agent_id)
    return JSONResponse(status_code=200, content={"stopped": True})


# --- the Agora join body ------------------------------------------------------------------


def agora_join_body(
    *,
    channel: str,
    uid: int,
    agent_uid: int,
    system_prompt: str,
    greeting: str,
    idle_timeout: int,
    pipeline_id: str,
    llm_style: str,
) -> dict[str, Any]:
    """
    The port of `AgoraSessionRepository.agoraJoinBody()`, with one addition the app cannot make:
    a token for the agent's own uid, signed with the App Certificate.

    `remote_rtc_uids` names the one uid rather than `"*"`. The agent then subscribes to exactly
    the person on this phone — with `"*"` it would answer anyone who joined the channel.

    `pipeline_id` names a published Agent Studio configuration and Agora treats it as the base:
    anything under `properties` overrides the corresponding saved setting. So the console owns
    the ASR/LLM/TTS choice and the turn-detection tuning, and the prompt and greeting are
    overridden on every call — they carry today's medicine schedule and differ per mode, so one
    published agent could not hold them.
    """
    properties: dict[str, Any] = {
        "channel": channel,
        "token": build_token(channel, agent_uid),
        "agent_rtc_uid": str(agent_uid),
        "remote_rtc_uids": [str(uid)],
        "enable_string_uid": False,
        "idle_timeout": idle_timeout,
        "llm": {
            "system_messages": system_messages(system_prompt, llm_style),
            "greeting_message": greeting,
            "failure_message": FAILURE_MESSAGE,
            "max_history": MAX_HISTORY,
        },
        "advanced_features": {
            # Lets the agent tell a pause from an ending, so it stops talking over someone who
            # speaks slowly — which on this call is everyone.
            "enable_aivad": True,
            # Transcripts come back over the RTC data stream, which is what
            # ConversationMessageParser reads. Turning RTM on moves them to the Signaling channel
            # and needs the Signaling SDK in the app.
            "enable_rtm": False,
        },
    }

    if llm_style == "gemini":
        # Agora reads `style` to decide which dialect it is speaking to the vendor.
        properties["llm"]["style"] = "gemini"

    if not pipeline_id:
        # Only when there is no published agent to take the vendors from. Sending them anyway
        # would override the console's choice — the exact thing the Studio agent exists to avoid.
        properties["asr"] = {
            "credential_mode": "managed",
            "vendor": MANAGED_ASR_VENDOR,
            "language": MANAGED_ASR_LANGUAGE,
        }
        properties["tts"] = {"credential_mode": "managed", "vendor": MANAGED_TTS_VENDOR}
        properties["llm"]["credential_mode"] = "managed"
        properties["llm"]["vendor"] = MANAGED_LLM_VENDOR
        properties["llm"]["params"] = {"model": MANAGED_LLM_MODEL}

    body: dict[str, Any] = {
        # Agora rejects a reused name while the previous agent is still alive, so it carries the
        # channel — which is already unique per call.
        "name": f"sentri-{channel}",
        "properties": properties,
    }
    if pipeline_id:
        body["pipeline_id"] = pipeline_id
    return body


def system_messages(system_prompt: str, llm_style: str) -> list[dict[str, Any]]:
    """
    The prompt in whichever shape the configured LLM family expects.

    Gemini has no `system` role — the instruction goes in as a `user` turn whose content is a
    `parts` array. Handing it an OpenAI-shaped message does not fail loudly; the prompt is simply
    not applied, and the agent takes the call as a generic assistant with no medicine schedule
    and none of the safety rules. The app decides which shape via `agoraLlmStyle` and tells this
    service in `llm_style`; both ends must agree.
    """
    if llm_style == "gemini":
        return [{"role": "user", "parts": [{"text": system_prompt}]}]
    return [{"role": "system", "content": system_prompt}]


def extract_agent_id(payload: dict[str, Any]) -> str:
    """
    Agora's `join` response has changed field name across API versions, so accept either and let
    a genuine absence be the error rather than a rename.
    """
    for key in ("agent_id", "agentId", "id"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""
