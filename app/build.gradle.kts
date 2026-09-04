import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val panelitoLocalProperties = Properties().also { properties ->
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { properties.load(it) }
    }
}

fun requiredPanelitoProperty(name: String): String =
    panelitoLocalProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Falta la propiedad obligatoria $name en local.properties")

fun quoteBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "servicoop.comunic.panelito"
    compileSdk = 34

    defaultConfig {
        applicationId = "servicoop.comunic.panelito"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "PANELITO_MQTT_BROKER_URL",
            quoteBuildConfig(requiredPanelitoProperty("panelito.mqtt.url")),
        )
        buildConfigField(
            "String",
            "PANELITO_MQTT_USERNAME",
            quoteBuildConfig(requiredPanelitoProperty("panelito.mqtt.username")),
        )
        buildConfigField(
            "String",
            "PANELITO_MQTT_PASSWORD",
            quoteBuildConfig(requiredPanelitoProperty("panelito.mqtt.password")),
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Registro de tarea para compatibilidad con herramientas que esperan 'testClasses'
tasks.register("testClasses") {
    description = "Alias para tareas de compilación de tests unitarios"
    group = "verification"
    dependsOn(tasks.matching {
        it.name.startsWith("compile") && it.name.endsWith("UnitTestSources")
    })
}
