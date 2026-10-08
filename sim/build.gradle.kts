plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

// La logique du bot (sans dépendance Android) est testée ici, telle qu'elle est embarquée dans l'APK.
sourceSets.main {
    kotlin.srcDir("../app/src/main/kotlin/fr/triche/stack/logic")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    // dossier de vraies images (hors dépôt) pour le rejeu, facultatif
    systemProperty("stack.frames", System.getenv("STACK_FRAMES") ?: "")
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
