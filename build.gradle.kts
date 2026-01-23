plugins {
    id("java")
}

group = "net.nova.gaytale.packetprox"
version = "0.0.2"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("/libs/HytaleServer.jar"))
}