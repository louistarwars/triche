plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

// La logique du bot (sans dépendance Android) est testée ici, telle qu'elle est embarquée dans l'APK.
sourceSets.main {
    kotlin.srcDir("../app/src/main/kotlin/fr/triche/maths/logic")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
