import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing config is read from keystore.properties at the repo root
// (git-ignored). When the file is absent (e.g. fresh clone / CI without
// secrets) the release build is simply left unsigned instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

// ── Núcleo Rust (crates/ffi) por UniFFI ──────────────────────────────────────────────────────────────
// O formato .tkeys e a criptografia vêm do MESMO código do desktop (localkeys-core). Três tarefas, nesta ordem:
//   cargoBuildHost  → a lib do computador (os testes JVM carregam por JNA) e a base dos bindings;
//   generateUniffi  → os bindings Kotlin (app/src/generated/uniffi, fora do git);
//   cargoNdk        → as libs .so de cada ABI (app/src/main/jniLibs, fora do git), sempre em release.
// Precisa de: Rust (rustup) com os targets *-linux-android, `cargo install cargo-ndk` e o NDK do Android.
// `-PskipNative` pula as três (quando os artefatos já foram gerados).
val skipNative = providers.gradleProperty("skipNative").isPresent
val ffiDir = rootProject.layout.projectDirectory.dir("../crates/ffi")
val cargoTarget = layout.buildDirectory.dir("cargo")
val hostLibDir = layout.buildDirectory.dir("cargo/debug")
val generatedKotlin = layout.projectDirectory.dir("src/generated/uniffi/kotlin")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val hostLibName = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "localkeys_ffi.dll"
        it.contains("mac") -> "liblocalkeys_ffi.dylib"
        else -> "liblocalkeys_ffi.so"
    }
}
val ffiSources = fileTree(rootProject.layout.projectDirectory.dir("..")) {
    include("crates/ffi/src/**", "crates/ffi/Cargo.*", "crates/ffi/uniffi.toml", "crates/core/src/**", "crates/core/Cargo.toml")
}

val cargoBuildHost = tasks.register<Exec>("cargoBuildHost") {
    group = "native"
    workingDir = ffiDir.asFile
    environment("CARGO_TARGET_DIR", cargoTarget.get().asFile.absolutePath)
    commandLine("cargo", "build", "--locked")
    inputs.files(ffiSources)
    outputs.dir(hostLibDir)
    enabled = !skipNative
}

val generateUniffi = tasks.register<Exec>("generateUniffi") {
    group = "native"
    dependsOn(cargoBuildHost)
    workingDir = ffiDir.asFile
    environment("CARGO_TARGET_DIR", cargoTarget.get().asFile.absolutePath)
    commandLine(
        "cargo", "run", "--locked", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", hostLibDir.get().file(hostLibName).asFile.absolutePath,
        "--language", "kotlin", "--no-format",
        "--config", ffiDir.file("uniffi.toml").asFile.absolutePath,
        "--out-dir", generatedKotlin.asFile.absolutePath,
    )
    inputs.files(ffiSources)
    outputs.dir(generatedKotlin)
    enabled = !skipNative
}

// O cargo-ndk acha o NDK em $ANDROID_NDK_HOME ou em $ANDROID_HOME/ndk/<versão>.
val sdkDir = androidComponents.sdkComponents.sdkDirectory
val cargoNdk = tasks.register<Exec>("cargoNdk") {
    group = "native"
    workingDir = ffiDir.asFile
    environment("CARGO_TARGET_DIR", cargoTarget.get().asFile.absolutePath)
    if (System.getenv("ANDROID_NDK_HOME") == null) environment("ANDROID_HOME", sdkDir.get().asFile.absolutePath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "-t", "x86",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release", "--locked",
    )
    inputs.files(ffiSources)
    outputs.dir(jniLibsDir)
    enabled = !skipNative
}

tasks.named("preBuild") { dependsOn(generateUniffi, cargoNdk) }
tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    // JNA acha a lib do computador por aqui (no Android, ela vem do jniLibs do APK).
    systemProperty("jna.library.path", hostLibDir.get().asFile.absolutePath)
}

android {
    namespace = "com.localkeys.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localkeys.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 16
        versionName = "0.8.0"

        vectorDrawables { useSupportLibrary = true }
        // Smoke test no emulador (CI): abrir o app de verdade e ver a UI subir.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // A keystore é PKCS12 (formato que o keytool moderno também gera).
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Auto-sign when keystore.properties is present; otherwise unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    // Os bindings do UniFFI (gerados pela tarefa generateUniffi; fora do git).
    sourceSets["main"].kotlin.srcDirs("src/generated/uniffi/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    testOptions {
        unitTests {
            // Os testes JVM usam org.json (real, do Maven) e o lazysodium-java,
            // que carrega o libsodium nativo via resource-loader — nada de stubs.
            isReturnDefaultValues = false
            isIncludeAndroidResources = false
            // stdout/stderr dos testes aparecem no log do CI (essencial p/ depurar).
            all { it.testLogging.showStandardStreams = true }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // bcprov-jdk18on e o transitivo jspecify trazem o mesmo manifest OSGi
            // multi-release; conteúdo irrelevante em runtime, só duplica no merge.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // ── AndroidX core ────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Idioma por app (AppCompatDelegate.setApplicationLocales): no Android 13+ usa o LocaleManager do sistema;
    // antes dele, o AppCompat guarda a escolha e aplica nas AppCompatActivity.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // Material XML themes (Theme.Material3.DayNight) — necessárias para o tema
    // base/splash definidos em res/values/themes.xml.
    implementation("com.google.android.material:material:1.12.0")

    // ── Compose ──────────────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Ícones por tipo de item (VpnKey/Description/CreditCard/Badge etc.) e
    // ações (busca, estrela, editar/excluir). O R8 poda os não usados.
    implementation("androidx.compose.material:material-icons-extended")

    // ── Cripto: o núcleo Rust (crates/ffi) por UniFFI ────────────────────────
    // O UniFFI carrega a lib nativa por JNA; o @aar do JNA é o que entrega a libjnidispatch do Android.
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // ── Biometria (desbloqueio sem senha) ────────────────────────────────
    implementation("androidx.biometric:biometric:1.1.0")

    // ── Import: Argon2id do Bitwarden (export cifrado) ───────────────────
    // A JVM/Android não tem Argon2 no JCE; o BouncyCastle é a implementação
    // auditada. Usado só como classe (nunca registrado como provider do SO).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── Credential Manager: o Local Keys como provedor de senhas e passkeys (Android 14+) ──
    implementation("androidx.credentials:credentials:1.6.0")
    // Credential Exchange (FIDO CXF): importar senhas e passkeys de outro gerenciador (o do Google).
    implementation("androidx.credentials.providerevents:providerevents:1.0.0-beta01")
    implementation("androidx.credentials.providerevents:providerevents-play-services:1.0.0-beta01")

    // ── QR do 2FA (otpauth://) pela câmera, sem Play Services ────────────
    // zxing-android-embedded: a tela de leitura (câmera) em cima do zxing-core, que é Java puro. Apache-2.0.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // ── SAF (abrir/criar o vault .tkeys no armazenamento do usuário) ─────
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ── Persistência de preferências (URI do vault, opt-ins) ─────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Kotlin coroutines ────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Testes (JVM) ─────────────────────────────────────────────────────
    // A lógica pura — header/cripto do .tkeys, TOTP, model do vault — roda sem
    // Android. org.json vem do Maven porque o do SDK é um stub que lança em
    // teste unitário. lazysodium-java fornece o libsodium nativo para a JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    // BouncyCastle roda puro na JVM (mesmo código do Argon2id do app).
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── Testes instrumentados (smoke no emulador do CI) ──────────────────
    // Rodam o APK de verdade num emulador Android e pegam crash de startup
    // (ex.: o Application inexistente do manifest que matou a v0.1.0 na hora).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // GrantPermissionRule: o smoke concede a permissão de notificação, senão o diálogo do sistema cobre o app.
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // ── Debug / preview ──────────────────────────────────────────────────
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
