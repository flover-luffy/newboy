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
    
    // 统一JSON解析库 - Jackson
    implementation("com.fasterxml.jackson.core:jackson-core:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.1")
    
    // SLF4J日志框架
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("ch.qos.logback:logback-classic:1.2.12")
    
    // JUnit 5 测试框架
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.2")
    
    // Mirai框架依赖
    compileOnly("net.mamoe:mirai-core-api:2.16.0")
    compileOnly("net.mamoe:mirai-console:2.16.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(18)
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xdiags:verbose"))
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 配置测试任务使用JUnit 5
tasks.test {
    useJUnitPlatform()
}

// 添加运行测试的任务
tasks.register<JavaExec>("runWeiboTest") {
    group = "application"
    description = "Run Weibo API test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.WeiboApiTest")
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("runExpressImageTest") {
    group = "application"
    description = "Run EXPRESSIMAGE message parsing test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.SimpleExpressImageTest")
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("runVideoTest") {
    group = "application"
    description = "Run VIDEO message parsing test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.SimpleVideoTest")
    jvmArgs = listOf("-Dfile.encoding=UTF-8")
}
