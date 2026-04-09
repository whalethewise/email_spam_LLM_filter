plugins {
    `java-library`
    id("io.spring.dependency-management")
}

group = "ca.aksentiev"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api("org.springframework.ai:spring-ai-starter-model-ollama")
    api("org.springframework.boot:spring-boot-starter-mail")
    api("org.springframework:spring-context")
    api("org.springframework.boot:spring-boot")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("org.yaml:snakeyaml")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
