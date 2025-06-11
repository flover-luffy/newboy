plugins {
    val kotlinVersion = "1.9.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.16.0"

}

group = "net.lawaxi"
version = "0.1.11-dev7"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
}

dependencies {
    api("cn.hutool:hutool-all:5.8.20")
    api(files("libs/wifeOttery48-0.1.9-test4-mirai2.jar"))
    implementation("com.belerweb:pinyin4j:2.5.0")
    implementation("net.coobird:thumbnailator:0.4.14")
}
