plugins {
    id("org.springframework.boot") version "3.2.4" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
    id("com.google.cloud.tools.jib") version "3.4.1" apply false
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
    kotlin("plugin.jpa") version "1.9.24" apply false
}

allprojects {
    group = "com.circleguard"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:3.2.4"))
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("com.h2database:h2")
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
            jvmTarget = "17"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    if (project.path.startsWith(":services:") && project.name != "services") {
        apply(plugin = "com.google.cloud.tools.jib")
        configure<com.google.cloud.tools.jib.gradle.JibExtension> {
            to {
                image = "jrivera340/${project.name}"
                tags = setOf("latest", "v1.0.${System.getenv("BUILD_NUMBER") ?: "0"}")
                auth {
                    username = System.getenv("DOCKER_USER")
                    password = System.getenv("DOCKER_PASS")
                }
            }
            from {
                image = "eclipse-temurin:17-jre-alpine"
            }
            container {
                mainClass = "com.circleguard.${project.name.replace("circleguard-", "").replace("-service", "")}.${project.name.replace("circleguard-", "").split("-").map { it.replaceFirstChar { it.uppercase() } }.joinToString("")}Application"
                jvmFlags = listOf("-Xms512m", "-Xdebug")
                ports = listOf(8080)
            }
            allowInsecureRegistries = true
            httpTimeout = 30000
        }
    }
}
