plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.aravind.hendrifs"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aravind.hendrifs"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")   // installable straight away; use a real key before publishing
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
