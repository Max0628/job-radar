import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    id("org.springframework.boot") apply false
}

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api("org.postgresql:postgresql")
    // 只給 JobExistenceRepository 用（唯一跨 collector/worker 共用的 Spring 元件，見該類別
    // 的 javadoc）。common 其餘部分維持框架無關的純 POJO/序列化風格，不因此擴大範圍。
    api("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
