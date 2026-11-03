import com.google.protobuf.gradle.proto

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

android {
    namespace = "com.robo.phonegateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robo.phonegateway"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // AppAuth's redirect receiver; must match the authentik blueprint
        // (infra/cluster-config/authentik/blueprints/phone-gateway.yaml).
        manifestPlaceholders["appAuthRedirectScheme"] = "com.robo.phonegateway"
        // MapLibre ships native code for four ABIs (~60 MB); phones are arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Build from the one schema at the robo-services root, so the app can never
    // drift from what kreceiver decodes. Copied alone into the build dir: protoc
    // treats a source dir as an include path, and the repo root holds build output.
    sourceSets["main"].proto { srcDir(layout.buildDirectory.dir("telemetry-proto")) }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val syncTelemetryProto by tasks.registering(Sync::class) {
    from(rootDir.resolve("../../../../telemetry.proto"))
    into(layout.buildDirectory.dir("telemetry-proto"))
}
tasks.configureEach {
    if (name.startsWith("generate") && name.endsWith("Proto") || name.startsWith("extract") && name.endsWith("Proto")) {
        dependsOn(syncTelemetryProto)
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks {
        all().configureEach {
            builtins { create("java") { option("lite") } }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("net.openid:appauth:0.11.1")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    // session route maps: free, no API key; tiles from OpenStreetMap (see RouteMap.kt)
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub off-device
}
