plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("com.twilio.sdk:twilio:10.1.1")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-freemarker")
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework:spring-aspects")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Estos dos tests levantan el contexto Spring completo (Kafka + mail + Twilio) y
// dependen de infraestructura/credenciales reales; se ejecutan como integración,
// no en el task unitario del pipeline.
tasks.withType<Test> {
    filter {
        excludeTestsMatching("*ExposureNotificationListenerTest")
        excludeTestsMatching("*NotificationRetryTest")
        isFailOnNoMatchingTests = false
    }
}
