import com.android.build.api.dsl.ApplicationExtension

apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")

extensions.configure<ApplicationExtension>("android") {
    namespace = "tech.stpnk.mcd"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.stpnk.mcd"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
