import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.3.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    // Without this the @Serializable classes get no serializer at all, and every
    // decode throws "Serializer for class 'X' is not found" at runtime. The
    // plugin jar is already on the buildscript classpath above; it has to be
    // applied here too, or the annotation is silently inert.
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    // Kotlin compiles @Serializable without the compiler plugin, so a missing
    // plugin still builds green and only fails at runtime. This fails the build
    // instead: every @Serializable type must have a generated serializer in the
    // compiled output, or the catalogue cannot load on device.
    tasks.register("verifySerializers") {
        dependsOn(tasks.named("compileDebugKotlin"))
        doLast {
            val compileTask = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileDebugKotlin").get()
            val outDir = compileTask.destinationDirectory.get().asFile
            val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
            val expected = mutableSetOf<String>()
            sources.forEach { file ->
                Regex("@Serializable\\s*(?:\\([^)]*\\))?\\s*(?:data\\s+)?class\\s+(\\w+)")
                    .findAll(file.readText())
                    .forEach { expected += it.groupValues[1] }
            }
            val absent = expected.filter { name ->
                !outDir.walkTopDown().any { f -> f.name == "$name\$\$serializer.class" }
            }
            if (absent.isNotEmpty()) {
                throw GradleException(
                    "No generated serializer for: ${absent.joinToString()}. " +
                        "The serialization compiler plugin is not applied to this module."
                )
            }
            logger.lifecycle("  serializers verified for ${expected.size} @Serializable types")
        }
    }
    tasks.named("check") { dependsOn("verifySerializers") }

    cloudstream {
        // GITHUB_REPOSITORY in Actions is a bare slug ("owner/repo"), not a URL.
        // Normalizing here fixes plugins.json download `url` fields pointing at the wrong host (404 on install).
        // The forge host comes from GITHUB_SERVER_URL so Forgejo/Gitea instances (which expose a
        // different raw-link layout than github.com) get a raw link that actually resolves.
        val repoSlug = System.getenv("GITHUB_REPOSITORY")?.trim().orEmpty()
        val repoHost = System.getenv("GITHUB_SERVER_URL")?.trim()
            ?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/').orEmpty()
            .lowercase()
        when {
            repoSlug.startsWith("http://") || repoSlug.startsWith("https://") -> setRepo(repoSlug)
            repoSlug.contains("/") && (repoHost.isEmpty() || repoHost == "github.com") ->
                setRepo(repoSlug.substringBefore('/'), repoSlug.substringAfter('/'), "github")
            repoSlug.contains("/") ->
                setRepo(repoSlug.substringBefore('/'), repoSlug.substringAfter('/'), "gitea-$repoHost")
            else -> setRepo("decodede", "extensions", "github")
        }
        authors = listOf("dronzer11")
    }

    android {
        namespace = "com.megix"
        compileSdk = 36
        defaultConfig {
            minSdk = 21
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                listOf(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            )
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:1.22.2")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("org.mozilla:rhino:1.8.1")
        implementation("androidx.annotation:annotation:1.10.0")
        implementation("androidx.browser:browser:1.8.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
