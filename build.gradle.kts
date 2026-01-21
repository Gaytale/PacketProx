plugins {
    id("java")
}

group = "net.nova.gaytale.packetprox"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("/libs/HytaleServer.jar"))

    compileOnly("net.fabricmc:sponge-mixin:0.16.5+mixin.0.8.7")
}