plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tuidev.ramlock"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tuidev.ramlock"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room3.runtime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

}


abstract class CargoNdkTask @javax.inject.Inject constructor(
    private val execOperations: org.gradle.process.ExecOperations
) : DefaultTask() {

    @get:InputDirectory
    abstract val rustSrcDir: DirectoryProperty

    @get:InputFile
    abstract val cargoToml: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val releaseMode: Property<Boolean>

    @get:Input
    abstract val targets: ListProperty<String>

    @TaskAction
    fun build() {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val cargoCommand = if (isWindows) "cargo.exe" else "cargo"
        val outDir = outputDirectory.get().asFile.absolutePath

        val args = mutableListOf(cargoCommand, "ndk")
        for (target in targets.get()) {
            args.add("-t")
            args.add(target)
        }
        args.add("-o")
        args.add(outDir)
        args.add("build")
        if (releaseMode.get()) {
            args.add("--release")
        }

        execOperations.exec {
            workingDir = cargoToml.get().asFile.parentFile
            commandLine(args)
        }.assertNormalExitValue()
    }
}

androidComponents {
    onVariants { variant ->
        val isRelease = variant.name.contains("release", ignoreCase = true)
        val cargoTask = tasks.register<CargoNdkTask>("buildCargo${variant.name.replaceFirstChar { it.uppercase() }}") {
            group = "rust"
            description = "Compiles Rust code for variant ${variant.name} using cargo-ndk"
            rustSrcDir.set(file("${project.rootDir}/rust_core/src"))
            cargoToml.set(file("${project.rootDir}/rust_core/Cargo.toml"))
            releaseMode.set(isRelease)
            targets.set(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
            outputDirectory.set(layout.buildDirectory.dir("intermediates/rustJniLibs/${variant.name}"))
        }

        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            cargoTask,
            CargoNdkTask::outputDirectory
        )
    }
}