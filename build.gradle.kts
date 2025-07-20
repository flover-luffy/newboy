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
    
    // 异步HTTP客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")
    
    // 高性能JSON解析库 (可选，用于性能敏感场景)
    implementation("com.fasterxml.jackson.core:jackson-core:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.1")
    
    // 测试依赖
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}

// 添加运行抖音API测试的任务
tasks.register<JavaExec>("runDouyinTest") {
    group = "application"
    description = "Run Douyin API test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.DouyinAPITest")
}

tasks.register<JavaExec>("runDouyinCookieTest") {
    group = "application"
    description = "Run Douyin Cookie simplified configuration test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.DouyinCookieTest")
}

tasks.register<JavaExec>("runDouyinCookieStandaloneTest") {
    group = "application"
    description = "Run Douyin Cookie standalone test (no Mirai dependencies)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.DouyinCookieStandaloneTest")
}

tasks.register<JavaExec>("runDouyinThreeParamDemo") {
    group = "application"
    description = "Run Douyin Three Parameter Cookie Demo"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.DouyinThreeParamCookieDemo")
}

tasks.register<JavaExec>("runDouyinRealCookieTest") {
    group = "application"
    description = "Run Douyin Real Cookie API Test"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.DouyinRealCookieTest")
}

tasks.register<JavaExec>("runDouyinAdvancedAPITest") {
    group = "application"
    description = "Run Douyin Advanced API Test with multiple endpoints"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.DouyinAdvancedAPITest")
}

tasks.register<JavaExec>("runDouyinOptimizedAPITest") {
    group = "application"
    description = "Run Douyin Optimized API Test with enhanced features"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.luffy.test.DouyinOptimizedAPITest")
}
