plugins {
    id("java")
}

group = "net.nova.gaytale.packetprox"
version = "0.0.3"

repositories {
    mavenCentral()
    maven("https://maven.hytale.com/release")
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:2026.01.28-87d03be09")
}