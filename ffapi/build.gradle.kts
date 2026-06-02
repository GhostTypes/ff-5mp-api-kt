plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  `maven-publish`
}

android {
  namespace = "me.ghost.ffapi"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  // Pure-logic units (parsers, MachineInfo) are tested on the local JVM.
  testOptions { unitTests { isReturnDefaultValues = true } }

  // Expose the release variant as the published artifact.
  publishing { singleVariant("release") }
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
  }
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}

// Publish to mavenLocal() so the app workspace can consume it as
//   me.ghost:ff-5mp-api-kt:0.1.0
// via `./gradlew :ffapi:publishToMavenLocal`.
publishing {
  publications {
    register<MavenPublication>("release") {
      groupId = "me.ghost"
      artifactId = "ff-5mp-api-kt"
      version = "0.1.0"
      afterEvaluate { from(components["release"]) }
    }
  }
}
