plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.chaquo.python") }
android {
    namespace = "pl.zuza.midea"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.zuza.midearemote.luaexport"
        minSdk = 26
        targetSdk = 35
        versionCode = 230
        versionName = "2.3.0-lua-export"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // Exact upstream requirements, no substitutes or rewritten crypto.
            install("aiofiles")
            install("aiohttp")
            install("colorlog")
            install("defusedxml")
            install("ifaddr")
            install("pycryptodome")
            install("platformdirs")
            install("typing_extensions>=4.5.0")
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
