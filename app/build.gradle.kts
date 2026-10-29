plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "pl.zuza.midea"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.zuza.midea.readonly"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-dual"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
