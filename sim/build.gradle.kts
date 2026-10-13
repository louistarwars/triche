plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

// La logique du bot (sans dépendance Android) est testée ici, telle qu'elle est embarquée dans l'APK.
sourceSets.main {
    kotlin.srcDir("../app/src/main/kotlin/fr/triche/dice/logic")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    timeout.set(java.time.Duration.ofMinutes(5))
    systemProperty("junit.jupiter.execution.timeout.default", "90 s")
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
