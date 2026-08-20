import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    }
}

apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.lagradost.cloudstream3.gradle")

configure<CloudstreamExtension> {
    // এখানে আপনার অ্যাপের নাম ও প্যাকেজ ঠিক করুন
    setPluginClass("shimul.com.DesireMoviesPlugin")
    setPluginName("DesireMovies")
    setPluginDescription("Download Latest Bollywood, Hollywood and South Movies")
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
    val cloudstreamVersion = "master-SNAPSHOT"
    compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
}
