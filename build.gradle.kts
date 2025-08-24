plugins {
    val kotlinVersion = "2.0.21"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    id("net.mamoe.mirai-console") version "2.16.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(18))
    }
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
    
    // 异步HTTP客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")
    
    // JSON解析库
    implementation("com.google.code.gson:gson:2.10.1")
    
    // 高性能JSON解析库 (可选，用于性能敏感场景)
    implementation("com.fasterxml.jackson.core:jackson-core:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.1")
    
    // Spring框架依赖
    implementation("org.springframework:spring-context:5.3.23")
    implementation("org.springframework:spring-web:5.3.23")
    implementation("org.springframework:spring-webmvc:5.3.23")
    implementation("org.springframework:spring-beans:5.3.23")
    
    // SLF4J日志框架
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("ch.qos.logback:logback-classic:1.2.12")
    
    // JSR-250注解支持
    implementation("javax.annotation:javax.annotation-api:1.3.2")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(18)
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xdiags:verbose"))
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 添加运行测试的任务
tasks.register<JavaExec>("runWeiboTest") {
    group = "application"
    description = "Run Weibo API test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.WeiboApiTest")
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
}
