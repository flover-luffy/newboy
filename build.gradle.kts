plugins {
    val kotlinVersion = "2.0.21"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion

    id("net.mamoe.mirai-console") version "2.16.0"

}

group = "net.luffy"
version = "1.0.0"

repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://mcl.repo.mamoe.net/")
    mavenCentral()
}

dependencies {
    api("cn.hutool:hutool-all:5.8.38")
    implementation("com.belerweb:pinyin4j:2.5.0")
    implementation("net.coobird:thumbnailator:0.4.20")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

mirai {
    // 确保插件正确构建到 build/mirai 目录
}
