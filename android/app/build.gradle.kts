plugins {
    alias(libs.plugins.android.application)
}

// Local MFi runtime assets extracted from owner's device DiPlay preview APK or external DIPLAY_AUTH_ASSETS_DIR.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
    ?: file("../.local-auth-assets").takeIf { it.resolve("offline-mfi/identity.pk8").isFile && it.resolve("offline-mfi/certificate.p7b").isFile }?.canonicalFile

android {
    namespace = "com.carplay.siqirelay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nkwyy56.motou"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "1.1.0"
    }

    localAuthenticationAssets?.let {
        sourceSets.getByName("main").assets.directories.add(it.absolutePath)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
    testImplementation(libs.junit)
}
