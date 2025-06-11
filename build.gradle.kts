plugins {
    val kotlinVersion = "2.0.21"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.16.0"

}

group = "net.lawaxi"
version = "0.1.11-dev7"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://mcl.repo.mamoe.net/")
    mavenCentral()
}

dependencies {
    api("cn.hutool:hutool-all:6.0.0")
    api(files("libs/wifeOttery48-0.1.9-test4-mirai2.jar"))
    implementation("com.belerweb:pinyin4j:2.6.0")
    implementation("net.coobird:thumbnailator:0.4.20")
}
