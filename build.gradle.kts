plugins {
    java
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "dev.jobradar"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform {
            // 見 .gitlab-ci.yml：CI 的 Runner 用 Kubernetes executor 跑 job，沒有 Docker daemon
            // 可用，Testcontainers 測試（@Tag("requires-docker")）在那邊連不上，跳過。
            if (project.hasProperty("skipDockerTests")) {
                excludeTags("requires-docker")
            }
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
    }
}
