import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")   // ← এই লাইনটা নতুন যোগ করুন
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    }
}

apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.lagradost.cloudstream3.gradle")

// version নম্বরের জন্য integer ব্যবহার করুন
version = 1

configure<CloudstreamExtension> {
    description = "Download Latest Bollywood, Hollywood and South Movies"
    authors = listOf("shimul")

    // 0: Down, 1: Ok, 2: Slow, 3: Beta only
    status = 1

    tvTypes = listOf("Movie")
    language = "bn"   // যদি শুধু হিন্দি/বলিউড কনটেন্ট না হয়, "en" দিন
    iconUrl = "https://www.google.com/s2/favicons?domain=desiremovies.com&sz=%size%"
}

android {
    namespace = "shimul.com"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
}
