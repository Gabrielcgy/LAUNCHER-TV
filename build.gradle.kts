 plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdk = 33
    defaultConfig {
        applicationId = "com.example.lanzadortv"
        minSdk = 23
        targetSdk = 29
        versionCode = 2
        versionName = "2.0"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.cardview:cardview:1.0.0")
}
