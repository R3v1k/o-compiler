plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.revik.API"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("o.MainKt")
}

// `./gradlew run --args="examples/03_tricky.o"` resolves paths from the project root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
