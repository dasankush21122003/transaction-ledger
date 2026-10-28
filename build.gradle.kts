plugins {
    id("java")
    application
}

group = "com.ledger"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.h2database:h2:2.2.224")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.ledger.Main")
}

tasks.test {
    useJUnitPlatform()
}
