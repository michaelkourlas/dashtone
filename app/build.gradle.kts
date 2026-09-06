import java.util.Properties
import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("net.kourlas.oss-licenses-plugin")
}

// Release signing credentials live outside version control (see .gitignore) -- this file is
// absent for anyone who hasn't set up a release keystore locally (e.g. CI running debug builds,
// a fresh clone), in which case the release build type below simply stays unsigned, same as
// before this was wired up.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "net.kourlas.dashtone"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "net.kourlas.dashtone"
        minSdk = 28
        targetSdk = 36
        versionCode = 15
        versionName = "0.1.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("releaseStoreFile"))
                storePassword = keystoreProperties.getProperty("releaseStorePassword")
                keyAlias = keystoreProperties.getProperty("releaseKeyAlias")
                keyPassword = keystoreProperties.getProperty("releaseKeyPassword")
            }
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
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.mukeshsolanki.markdownView)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(project(":service"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// The About screen's markdown viewer reads these straight from assets rather than duplicating
// their content in source -- copied fresh from the repo root on every build so they can never
// drift out of sync with the real docs.
tasks.register<Delete>("cleanAssets") {
    delete("src/main/assets")
}

tasks.register<Copy>("copyToAssets") {
    from("../PRIVACY.md")
    from("../CHANGES.md")
    from("../NOTICE")
    from("../LICENSE.md")
    from("../HELP.md")
    into("src/main/assets")
}

tasks.getByName("preBuild") {
    dependsOn("copyToAssets")
}

tasks.getByName("copyToAssets") {
    dependsOn("cleanAssets")
}

// Turns net.kourlas.oss-licenses-plugin's per-variant dependency scan into PACKAGE_LICENSES.md,
// the same asset the About screen's "Third-party package licenses" entry displays. See
// licenses/packageLicenseParser.py for how each dependency's license is resolved -- it fails loudly
// on any license it doesn't recognize, rather than silently shipping an incomplete attribution list.
androidComponents {
    onVariants(selector().all()) { variant ->
        val variantName = variant.name

        val generatePackageLicenses =
            tasks.register<Exec>("generatePackageLicenses${variantName.capitalized()}") {
                commandLine(
                    "python",
                    "../licenses/packageLicenseParser.py",
                    variantName
                )
            }.get()

        tasks.matching { it.name == "${variant.name}OssLicensesTask" }
            .configureEach {
                this.dependsOn("cleanAssets")
                generatePackageLicenses.dependsOn(this)
            }

        tasks.matching { it.name == "generate${variant.name.capitalized()}Assets" }
            .configureEach { this.dependsOn(generatePackageLicenses) }
    }
}