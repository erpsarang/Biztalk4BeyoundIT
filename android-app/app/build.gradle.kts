plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.blueward.biztalk"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.blueward.biztalk"
        minSdk = 28 // Android 9+. MessagingStyle의 Person 정보를 읽는 데 필요하다
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // TODO: 실사용(4단계) 전에 고정 서명 키로 바꾼다. 키는 저장소에 넣지 않는다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.work:work-runtime:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // 안드로이드의 org.json은 JVM 단위 테스트에서 동작하지 않으므로 실제 구현을 넣는다.
    testImplementation("org.json:json:20240303")
}
