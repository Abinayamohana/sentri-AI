import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/**
 * Reads a setting from local.properties or gradle.properties, trying each spelling in turn.
 *
 * Both files are accepted because either is a reasonable place to put this, and the aliases
 * exist because the BuildConfig field names and the Gradle property names look similar enough
 * to swap by accident. Guessing right beats failing with an empty value at runtime.
 */
fun setting(vararg names: String): String {
    for (name in names) {
        val value = localProperties.getProperty(name) ?: project.findProperty(name)?.toString()
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return ""
}

// local.properties is gitignored, which makes it the better home for a personal token.
val huggingFaceToken: String = setting("hfToken", "HF_TOKEN", "huggingFaceToken", "modelAuthToken")

/**
 * A Hugging Face repo holding the models that exist nowhere public — the Whisper ExecuTorch
 * exports and the converted FunctionGemma bundle. Upload them once to a repo of your own and
 * the app fetches them from there; see MODELS.md.
 */
val huggingFaceModelRepo: String = setting("hfModelRepo", "HF_MODEL_REPO")

private val configuredBaseUrl: String = setting("modelBaseUrl", "MODEL_BASE_URL")

// A token pasted into the URL slot would otherwise reach the app and fail as a confusing
// "unknown protocol" at download time. Reject it here instead, without echoing the value.
val modelBaseUrl: String = when {
    configuredBaseUrl.isEmpty() -> ""
    configuredBaseUrl.startsWith("http://") || configuredBaseUrl.startsWith("https://") -> configuredBaseUrl
    else -> {
        logger.warn(
            "SentriAI: ignoring modelBaseUrl — it must start with http:// or https://. " +
                "If you meant to set a Hugging Face token, the key is hfToken."
        )
        ""
    }
}

// --- Agora Conversational AI ------------------------------------------------------------
//
// Three separate things, deliberately kept apart because they have very different blast radii:
//
// 1. `agoraAppId` — public by design. It identifies the project and has to be in the client to
//    create an RtcEngine at all. Safe in any build.
// 2. `agoraBackendUrl` — your own service. It mints RTC tokens and starts the conversational
//    agent, holding the App Certificate and the Customer ID/Secret so the app never does.
//    This is the supported production path; see AgoraSessionRepository for the two endpoints.
// 3. `agoraCustomerId` / `agoraCustomerSecret` — Agora RESTful API credentials. These can start
//    an agent on your account and bill it, so they are wired into the **debug** build only, as
//    a way to exercise the flow before the backend exists. Never set them for a release build.
val agoraAppId: String = setting("agoraAppId", "AGORA_APP_ID")
val agoraBackendUrl: String = setting("agoraBackendUrl", "AGORA_BACKEND_URL")
// The published Agent Studio agent to base each call on — Console → Agents → your agent →
// Embed Agent. Optional; without it the app sends its own ASR/LLM/TTS vendor configuration.
val agoraPipelineId: String = setting("agoraPipelineId", "AGORA_PIPELINE_ID")
// "gemini" when the agent's LLM is Gemini or Vertex AI, otherwise leave unset. This selects the
// shape of the system-prompt payload, which the two families spell differently — see
// AgoraConfig.LLM_STYLE for why getting it wrong fails silently.
val agoraLlmStyle: String = setting("agoraLlmStyle", "AGORA_LLM_STYLE")
val agoraCustomerId: String = setting("agoraCustomerId", "AGORA_CUSTOMER_ID")
val agoraCustomerSecret: String = setting("agoraCustomerSecret", "AGORA_CUSTOMER_SECRET")

android {
    namespace = "com.example.sentriai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.sentriai"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app fetches model files on first launch. Override in gradle.properties (or
        // with -PmodelBaseUrl=…) rather than editing this file. The Gemma bundles are gated on
        // HuggingFace and the Whisper .pte files are local exports, so in practice this points
        // at your own storage; see app/src/main/assets/README.md.
        buildConfigField("String", "MODEL_BASE_URL", "\"$modelBaseUrl\"")
        buildConfigField("String", "HF_MODEL_REPO", "\"$huggingFaceModelRepo\"")
        // Empty here so release builds never carry a token; the debug variant below overrides
        // it with whatever local.properties holds.
        buildConfigField("String", "HF_TOKEN", "\"\"")

        buildConfigField("String", "AGORA_APP_ID", "\"$agoraAppId\"")
        buildConfigField("String", "AGORA_BACKEND_URL", "\"$agoraBackendUrl\"")
        buildConfigField("String", "AGORA_PIPELINE_ID", "\"$agoraPipelineId\"")
        buildConfigField("String", "AGORA_LLM_STYLE", "\"$agoraLlmStyle\"")
        // Empty in every variant except debug — see the block below and the comment above.
        buildConfigField("String", "AGORA_CUSTOMER_ID", "\"\"")
        buildConfigField("String", "AGORA_CUSTOMER_SECRET", "\"\"")

        ndk {
            // The ExecuTorch AAR only ships libexecutorch.so for these two ABIs. Without the
            // filter the APK still gains armeabi-v7a/x86 folders from other dependencies, and
            // System.loadLibrary("executorch") then fails at runtime on those devices.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    androidResources {
        // Model artifacts are copied out of assets to filesDir before ExecuTorch can open them
        // (it takes filesystem paths, not asset paths). Keeping them uncompressed avoids the
        // aapt compressed-asset size limit and makes that first-run copy a straight byte copy.
        // "json" is here for the Whisper tokenizer: a compressed asset has no readable length,
        // so ModelStore cannot tell a complete extracted copy from a truncated one.
        noCompress += listOf("pte", "ptd", "bin", "task", "json")
    }

    buildTypes {
        debug {
            // Pulling a gated model straight from Hugging Face is a development convenience.
            // The token only reaches the debug APK, and the button that uses it is compiled
            // behind BuildConfig.DEBUG.
            buildConfigField("String", "HF_TOKEN", "\"$huggingFaceToken\"")

            // Lets a developer start a real conversational agent without standing the backend
            // up first. AgoraSessionRepository additionally refuses to use these unless
            // BuildConfig.DEBUG, so a stray gradle.properties entry cannot leak them into a
            // shipped build.
            buildConfigField("String", "AGORA_CUSTOMER_ID", "\"$agoraCustomerId\"")
            buildConfigField("String", "AGORA_CUSTOMER_SECRET", "\"$agoraCustomerSecret\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // The engine classes log through android.util.Log, which is an unimplemented stub in
            // the JVM test runtime. Returning defaults lets their pure logic be tested off-device.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    // viewModel() + collectAsStateWithLifecycle() for the transcription screen.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    debugImplementation(libs.androidx.compose.ui.tooling)

    // On-device inference. Pulls fbjni, soloader-nativeloader and its own R8 keep rules
    // transitively — nothing else to declare here.
    implementation(libs.pytorch.executorch.android)

    // Live voice for the companion/check-in call. Only the Agora layer touches this; the
    // passive on-device path does not know it exists.
    //
    // `voice-sdk` is an aggregator POM over the core plus every audio extension. Three of those
    // are for use cases this app does not have and cost ~14 MB of .so per ABI, so they are
    // excluded. What is kept is deliberate:
    //   - voice-rtc-basic: the SDK itself.
    //   - ains  (AI noise suppression) and aiaec (AI echo cancellation): both matter here. The
    //     phone is on speakerphone in a room, so the agent's own TTS is coming back down the
    //     mic; AUDIO_SCENARIO_AI_CLIENT expects these to be present to handle that well.
    implementation(libs.agora.rtc.voice) {
        // Lip sync — needs a video avatar, which this call does not have.
        exclude(group = "io.agora.rtc", module = "full-voice-drive")
        // 3D positional audio for one remote speaker on a phone speaker.
        exclude(group = "io.agora.rtc", module = "spatial-audio")
        // Voice changer / reverb effects.
        exclude(group = "io.agora.rtc", module = "audio-beauty")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}