import Dependencies.removeIncompatibleDependencies

plugins {
  id(Plugins.BuildPlugins.androidLib)
  id(Plugins.BuildPlugins.kotlinAndroid)
  id(Plugins.BuildPlugins.mavenPublish)
  jacoco
}

publishArtifact(Releases.Contrib.Barcode)

createJacocoTestReportTask()

android {
  namespace = "com.google.android.fhir.datacapture.contrib.views.barcode"
  compileSdk = Sdk.compileSdk
  defaultConfig {
    minSdk = Sdk.minSdk
    testInstrumentationRunner = Dependencies.androidJunitRunner
    // Need to specify this to prevent junit runner from going deep into our dependencies
    testInstrumentationRunnerArguments["package"] = "com.google.android.fhir.datacapture"
  }

  buildFeatures { viewBinding = true }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }
  packaging {
    resources.excludes.addAll(
      listOf(
        "META-INF/INDEX.LIST",
        "META-INF/ASL2.0",
        "META-INF/ASL-2.0.txt",
        "META-INF/LGPL-3.0.txt",
      ),
    )
  }

  configureJacocoTestOptions()

  testOptions { animationsDisabled = true }
  kotlin { jvmToolchain(11) }
}

configurations { all { removeIncompatibleDependencies() } }

dependencies {
  androidTestImplementation(Dependencies.mockitoInline)
  androidTestImplementation(libs.androidx.fragment.testing)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.ext.junit.ktx)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.truth)

  implementation(project(":datacapture"))
  implementation(Dependencies.Mlkit.barcodeScanning)
  implementation(Dependencies.Mlkit.objectDetection)
  implementation(Dependencies.Mlkit.objectDetectionCustom)
  implementation(Dependencies.material)
  implementation(Dependencies.timber)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core)
  implementation(libs.androidx.fragment)

  testImplementation(Dependencies.mockitoInline)
  testImplementation(Dependencies.mockitoKotlin)
  testImplementation(Dependencies.robolectric)
  testImplementation(libs.androidx.fragment.testing)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.truth)
}
