plugins {
    id("java")
    id("com.gradleup.shadow") version "9.3.0"
}

group = "dev.solarion"
version = "1.0-SNAPSHOT"

val repoUser = System.getenv("REPO_USERNAME") ?: project.findProperty("repoUser") as String? ?: project.findProperty("mavenUser") as String? ?: ""
val repoPass = System.getenv("REPO_PASSWORD") ?: project.findProperty("repoPass") as String? ?: project.findProperty("mavenPass") as String? ?: ""


repositories {
    maven {
        url = uri("https://repo.solarion.dev/repository/maven-snapshots/")
        name = "SolarionInteractive"
        credentials {
            username = repoUser
            password = repoPass
        }
    }
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    if(System.getenv("CI_COMMIT_SHORT_SHA") != null) {
        compileOnly("com.hypixel.hytale:Server:2026.01.17-4b0f30090")
    } else {
        implementation("com.hypixel.hytale:Server:2026.01.17-4b0f30090")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    mergeServiceFiles()
    minimize()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}