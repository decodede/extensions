import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
version = 2
cloudstream {
    description = """
        Stream anything with your Stremio addons.
    """.trimIndent()
    authors = listOf("cookie 🍪")
    status = 2
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/decodede/extensions/master/icons/StremioCS.png"
}
android {
    namespace = "com.stremiouniversal"
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
        freeCompilerArgs.addAll(listOf("-Xno-call-assertions","-Xno-param-assertions","-Xno-receiver-assertions"))
    }
}
