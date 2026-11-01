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
        versionCode = 16
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

/**
 * Produces every generated asset for one variant, into a directory of its own.
 *
 * The About screen's markdown viewer reads the repo's own documents straight from assets rather
 * than duplicating their content in source, so they are copied fresh on every build and can never
 * drift out of sync. PACKAGE_LICENSES.md alongside them turns net.kourlas.oss-licenses-plugin's
 * per-variant dependency scan into the list the "Third-party package licenses" entry displays --
 * see licenses/packageLicenseParser.py, which fails loudly on any license it doesn't recognize
 * rather than silently shipping an incomplete attribution list.
 *
 * One task for both jobs, with the directory as its single declared output, is what lets Gradle
 * track them: two tasks writing into one directory have no ordering between them, and the earlier
 * arrangement additionally had a Delete task wiping that directory out from under both.
 */
abstract class GenerateDocAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val docs: ConfigurableFileCollection

    /** The dependency scan this variant's license list is built from. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseReport: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val parserScript: RegularFileProperty

    /** The full license texts the parser splices in, keyed by name. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseTexts: ConfigurableFileCollection

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val pythonExecutable: Property<String>

    /** The parser resolves the dependency scan relative to the app project. */
    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @get:Inject
    abstract val processes: ExecOperations

    @TaskAction
    fun generate() {
        val destination = outputDir.get().asFile
        files.sync {
            from(docs)
            into(destination)
        }
        processes.exec {
            workingDir = workingDirectory.get().asFile
            commandLine(
                pythonExecutable.get(),
                parserScript.get().asFile.absolutePath,
                variantName.get(),
                destination.resolve("PACKAGE_LICENSES.md").absolutePath,
            )
        }
    }
}

// Override with -Pdashtone.python=python3 where the interpreter goes by that name.
val pythonInterpreter = providers.gradleProperty("dashtone.python").orElse("python")

androidComponents {
    onVariants(selector().all()) { variant ->
        val generateDocAssets =
            tasks.register<GenerateDocAssets>("generate${variant.name.capitalized()}DocAssets") {
                docs.from(
                    rootProject.file("PRIVACY.md"),
                    rootProject.file("CHANGES.md"),
                    rootProject.file("NOTICE"),
                    rootProject.file("LICENSE.md"),
                    rootProject.file("HELP.md"),
                )
                licenseReport.set(
                    layout.buildDirectory.file(
                        "generated/oss_licenses/${variant.name}/dependencies_with_licenses.json",
                    ),
                )
                parserScript.set(rootProject.layout.projectDirectory.file("licenses/packageLicenseParser.py"))
                licenseTexts.from(rootProject.fileTree("licenses") { include("*.txt") })
                variantName.set(variant.name)
                pythonExecutable.set(pythonInterpreter)
                workingDirectory.set(layout.projectDirectory)
                outputDir.set(layout.buildDirectory.dir("generated/docAssets/${variant.name}"))
                // The report is produced by the oss-licenses plugin's own task, which nothing else
                // here can point Gradle at.
                dependsOn("${variant.name}OssLicensesTask")
            }
        // Registers the directory as a variant asset source, which is what gives merge<Variant>Assets
        // both the dependency on this task and the up-to-date checking a hand-wired dependsOn lacked.
        variant.sources.assets?.addGeneratedSourceDirectory(generateDocAssets, GenerateDocAssets::outputDir)
    }
}