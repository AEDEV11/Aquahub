import java.util.Properties
import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
fun configuredValue(name: String): String =
    localProperties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: ""

val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: System.getenv("MAPS_API_KEY") ?: ""
// Public verification material only. The matching private signing key belongs
// exclusively in the server function environment and is never shipped here.
val offlinePosVerifyPublicKey = configuredValue("OFFLINE_POS_VERIFY_PUBLIC_KEY")
// Legacy SUPABASE_URL/SUPABASE_ANON_KEY remain a production-only fallback for
// existing developer machines. QA never inherits these production values.
val productionSupabaseUrl = configuredValue("SUPABASE_PRODUCTION_URL")
    .ifBlank { configuredValue("SUPABASE_URL") }
val productionSupabaseAnonKey = configuredValue("SUPABASE_PRODUCTION_ANON_KEY")
    .ifBlank { configuredValue("SUPABASE_ANON_KEY") }
val qaSupabaseUrl = configuredValue("SUPABASE_QA_URL")
val qaSupabaseAnonKey = configuredValue("SUPABASE_QA_ANON_KEY")
val productionSupabaseHost = "pfkxrggexueltytxtqil.supabase.co"
fun supabaseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
fun supabaseHost(value: String): String? = supabaseUri(value)?.host?.lowercase()
fun isHttpsSupabaseEndpoint(value: String): Boolean {
    val uri = supabaseUri(value) ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.port == -1 &&
        uri.query == null &&
        uri.fragment == null &&
        uri.path.orEmpty().trimEnd('/').isEmpty()
}
fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val validateProductionReleaseSupabaseConfig by tasks.registering {
    group = "verification"
    description = "Validates the production Supabase endpoint before building the production release variant."
    doLast {
        check(productionSupabaseUrl.isNotBlank() && productionSupabaseAnonKey.isNotBlank()) {
            "Production release requires SUPABASE_PRODUCTION_URL and SUPABASE_PRODUCTION_ANON_KEY (or legacy production-only SUPABASE_URL/SUPABASE_ANON_KEY)."
        }
        check(isHttpsSupabaseEndpoint(productionSupabaseUrl)) {
            "Production Supabase endpoint must be an HTTPS project origin without credentials, a custom port, query, or path."
        }
        check(supabaseHost(productionSupabaseUrl) == productionSupabaseHost) {
            "Production release builds must target the configured AquaHub production Supabase project."
        }
    }
}

val validateQaReleaseSupabaseConfig by tasks.registering {
    group = "verification"
    description = "Validates the QA Supabase endpoint before building the QA release variant."
    doLast {
        check(qaSupabaseUrl.isNotBlank() && qaSupabaseAnonKey.isNotBlank()) {
            "QA release requires SUPABASE_QA_URL and SUPABASE_QA_ANON_KEY."
        }
        check(isHttpsSupabaseEndpoint(qaSupabaseUrl)) {
            "QA Supabase endpoint must be an HTTPS project origin without credentials, a custom port, query, or path."
        }
        check(supabaseHost(qaSupabaseUrl) != productionSupabaseHost) {
            "QA release builds must not target the AquaHub production Supabase project."
        }
    }
}

tasks.matching { it.name == "preProductionReleaseBuild" }.configureEach {
    dependsOn(validateProductionReleaseSupabaseConfig)
}
tasks.matching { it.name == "preQaReleaseBuild" }.configureEach {
    dependsOn(validateQaReleaseSupabaseConfig)
}

android {
    namespace = "com.aesprt.aquahub"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aesprt.aquahub"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "aq-1.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "OFFLINE_POS_VERIFY_PUBLIC_KEY", buildConfigString(offlinePosVerifyPublicKey))
    }

    flavorDimensions += "environment"
    productFlavors {
        create("production") {
            dimension = "environment"
            buildConfigField("boolean", "QA_BUILD", "false")
            buildConfigField("String", "SUPABASE_URL", buildConfigString(productionSupabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(productionSupabaseAnonKey))
        }
        create("qa") {
            dimension = "environment"
            applicationIdSuffix = ".qa"
            buildConfigField("boolean", "QA_BUILD", "true")
            buildConfigField("String", "SUPABASE_URL", buildConfigString(qaSupabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(qaSupabaseAnonKey))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Preserve/package symbols for native inputs that provide them so
            // Play Console can symbolicate native crashes where possible.
            ndk {
                debugSymbolLevel = "FULL"
            }
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
}

// QA deliberately has no Firebase app registration. Its manifest removes
// Firebase auto-init components, and these tasks otherwise fail package-name
// matching against the production-only google-services.json.
tasks.configureEach {
    if (name.startsWith("processQa") && name.endsWith("GoogleServices")) enabled = false
    if (name.contains("Qa", ignoreCase = true) && name.contains("Crashlytics", ignoreCase = true)) enabled = false
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.compose.navigation)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coil
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // WorkManager keeps queued Room mutations reliable across process restarts.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Google Fonts
    implementation(libs.google.fonts)

    // Google Maps & Places
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.billing)
    implementation(libs.billing.ktx)
    implementation(libs.google.places)
    implementation(libs.maps.compose)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)

    // Supabase auth/profile bridge. Only the public client key is available here;
    // service-role credentials never belong in an Android client.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
