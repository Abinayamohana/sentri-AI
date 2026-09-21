# Model files

Whisper ships in the APK. The Gemma classifier model downloads at runtime.

Everything runs on device once present. The network is used only for that model download; no
audio or transcript ever leaves the phone.

| File | Purpose | Size | Source |
|------|---------|------|--------|
| `model.pte` | Whisper tiny.en encoder + decoder | 70 MiB | bundled in `assets/` |
| `whisper_preprocessor.pte` | log-mel spectrogram front-end | 77 KiB | bundled in `assets/` |
| `tokenizer.json` | Whisper vocab + BPE rules | 2.3 MiB | bundled in `assets/` |
| `gemma3-1b-it-q4.task` | decides whether an utterance is an emergency | 529 MiB | Hugging Face (gated) |

`ModelStore` treats a bundled file and a downloaded one identically, so moving a model between
`assets/` and a URL needs no code change beyond its catalog entry.

The Gemma classifier model is optional: without it detection falls back to the safe-word trigger
and the keyword scan, and the status badge reads *"Keyword detection only — classifier
absient"*. See `engine/EmergencyPipeline.kt` for how the detection stages fit together.

## Why Whisper is bundled

No public repo has a drop-in `whisper-tiny.en` ExecuTorch export:

- `larryliu0820/whisper-tiny-INT8-INT4-ExecuTorch-XNNPACK` has the right layout, including a
  `whisper_preprocessor.pte`, but is built from **multilingual `whisper-tiny`**, not `tiny.en`.
  That changes the vocab size (51865 vs 51864), the decoder start token, and requires
  language/task tokens in the forced prompt — none of which `WhisperTokenizer` handles.
- `software-mansion/react-native-executorch-whisper-tiny.en` is the right variant but a
  different runtime's export, with no separate preprocessor and different method signatures.
- `optimum/whisper-tiny.en` contains no `.pte` at all.

So these are local `optimum-cli` exports, and bundling is the only route that needs no hosting.

## Where the Gemma model comes from

- **`gemma3-1b-it-q4.task`** — `litert-community/Gemma3-1B-IT`, which is gated (`gated: auto`:
  licence acceptance plus a token). Anonymous requests get `401 GatedRepo`. The hub filename is
  `Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task`; the catalog maps it to the shorter local
  name. Use the in-app button below, or host a copy yourself.

## Configuration

Three settings, all optional, read from **either** `local.properties` or `gradle.properties`.
`local.properties` is gitignored, so secrets belong there.

```properties
# local.properties — never committed
hfToken=hf_xxxxxxxxxxxxxxxxxxxx      # Hugging Face read token
hfModelRepo=your-org/sentriai-models  # your repo, for models with no public source
```

```properties
# gradle.properties — tracked in git, so URL only, never a token
modelBaseUrl=https://your-bucket.s3.amazonaws.com/sentriai-models
```

`modelBaseUrl` is validated at configure time: anything not starting with `http://` or
`https://` is ignored with a warning, so a token pasted into the URL slot fails loudly at build
rather than confusingly at runtime.

For a file with both a hub source and a base URL, the hub is tried first and the base URL is
the fallback.

### Testing the download path locally

```bash
cd model-hosting && python3 -m http.server 8765
```

```properties
# local.properties
modelBaseUrl=http://10.0.2.2:8765     # emulator
# modelBaseUrl=http://192.168.x.x:8765  # physical device on your LAN
```

Debug builds permit cleartext for this; release builds stay HTTPS-only. Note that Python's
`http.server` ignores `Range` requests, so an interrupted download restarts instead of
resuming. Real storage handles ranges properly.

## Development shortcut: download from Hugging Face in-app

Debug builds show a **Download Emergency classifier** button under the engine status badge on
the assistant screen. It pulls the `.task` straight from the hub with a personal token, so a
test device can get the model without you standing up hosting first.

Put the token in `local.properties`, which is already gitignored:

```properties
hfToken=hf_xxxxxxxxxxxxxxxxxxxx
```

Then rebuild — the token is read at configure time, so editing `local.properties` needs a new
build, not just an app restart. Create the token at
[huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) with read access, and
accept the licence on
[litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT) — the
repo is gated, and a valid token without licence acceptance still returns
`401 GatedRepo`. The button reports that specific message rather than a bare 401, because the
two need different fixes.

The button disappears once the model is on disk, and is compiled out of release builds
entirely: `HF_TOKEN` is declared empty in `defaultConfig` and only overridden in the `debug`
build type, and the composable returns immediately unless `BuildConfig.DEBUG`.

This is a testing convenience, not the production path. It only offers models with an `hfRepo`
in `ModelCatalog`: currently only the classifier model.
The Whisper files are never offered — they are bundled, and no hub copy matches this app's
export.

One caveat: the catalog pins the classifier to
`Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task` (529 MiB) and checks the downloaded size
against it. Switching to another variant means updating `sizeBytes` too, or the file will be
treated as incomplete.

## Skipping the download

`ModelStore` also checks `/data/local/tmp/llm/`, which is the fastest way to swap one model
without a re-download:

```bash
adb shell mkdir -p /data/local/tmp/llm
adb push gemma3-1b-it-q4.task /data/local/tmp/llm/
```

## Integrity

Each catalog entry carries an expected size and SHA-256, verified once when the download
finishes. This matters more than it sounds: a captive portal or an expired signed URL answers
`200 OK` with an HTML body, and without the check that HTML gets saved as `model.pte` and
resurfaces much later as an unreadable-model crash with no obvious cause.

Bytes land in `<name>.part` and are renamed only after the digest matches, so a loader can
never open a half-written model. An interrupted download resumes with a `Range` request rather
than starting over.

If you re-export Whisper or re-convert a Gemma bundle, update `sizeBytes` and `sha256` in
`models/ModelCatalog.kt` or the new file will be rejected:

```bash
shasum -a 256 model.pte
stat -f%z model.pte        # macOS;  stat -c%s on Linux
```

Entries with a null `sha256` — currently only the classifier, since its exact build is your
choice — skip verification.

## Exporting Whisper

> **Prerequisites — avoid two install failures we already hit:**
> - **Use Python 3.10–3.12** (not 3.13/3.14). ExecuTorch has no wheels for 3.13+, so
>   `pip install optimum-executorch` fails with *"No matching distribution found for
>   executorch"*. macOS: `python3.12 -m venv ~/whisper-export-venv && source ~/whisper-export-venv/bin/activate`.
> - **Pin torch to 2.12.x for executorch 1.3.1.** Its metadata says `torch>=2.12.0a0`,
>   which lets pip grab torch 2.13.0 — an ABI mismatch that crashes with
>   *"Symbol not found: ...materialize_cow_storage ... Expected in libc10.dylib"*.
>   After installing, run `pip install "torch==2.12.1"` to force the matching build.

```bash
pip install optimum-executorch        # plus torch + transformers per its README
pip install "torch==2.12.1"           # match executorch 1.3.1 (our Android runtime)

# Encoder+decoder graph, XNNPACK backend, int8-dynamic-activation / int4-weight quant
optimum-cli export executorch \
  --model openai/whisper-tiny.en \
  --task automatic-speech-recognition \
  --recipe xnnpack \
  --qlinear 8da4w \
  --output_dir whisper_tiny_en_q4       # produces model.pte (~189 MB)

# Log-mel spectrogram preprocessor (80 mel bins for tiny)
python -m executorch.extension.audio.mel_spectrogram \
  --feature_size 80 --stack_output --max_audio_len 300 \
  --output_file whisper_preprocessor.pte
```

`tokenizer.json` is the one file with a working public URL — download it from
[openai/whisper-tiny.en](https://huggingface.co/openai/whisper-tiny.en).

### Verified Whisper signatures

Confirmed by introspecting the exported `model.pte`; `asr/WhisperModel.kt` is written against
exactly these:

- `encoder(mel[1,80,3000] f32) -> hidden[1,1500,384] f32`
- `text_decoder(input_ids[1,1] i64, hidden[1,1500,384] f32, cache_position[1] i64) -> logits[1,1,51864] f32`
  (stateful KV-cache in the module's mutable buffers)
- config: `decoder_start_token_id=50257`, `eos=50256`, `vocab_size=51864`, `max_seq_len=1024`

## Notes

- `.pte` and `.task` are kept uncompressed (`noCompress` in `build.gradle.kts`) so ExecuTorch
  and MediaPipe can mmap them.
- Only `arm64-v8a` and `x86_64` are built — run on a physical arm64 device, not an arm emulator.
- The download runs in the setup screen's `viewModelScope`, so backgrounding the app pauses it.
  Resume makes that survivable, but moving the transfer to a foreground service or WorkManager
  is the upgrade if users are downloading over slow connections.
