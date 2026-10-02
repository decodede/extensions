import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

version = 4

cloudstream {
    description = "Play anything your Stremio addons can find."
    authors = listOf("cookie 🍪")
    status = 2
    tvTypes = listOf("Movie", "TvSeries", "Other")
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/decodede/extensions/master/icons/stremio.png"
}

android {
    namespace = "com.stremio"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll(listOf("-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions"))
    }
}
