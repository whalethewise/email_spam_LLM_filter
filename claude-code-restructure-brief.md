# Claude Code Task: Restructure AIEmailProcessingPlatform as Multi-Module Gradle Project

## Context

AIEmailProcessingPlatform is a Spring Boot / Spring AI email filtering platform.
The goal is to extract shared domain classes into a new `email-filter-core` submodule
(plain Java library, no Spring Boot), while the existing application code stays in a
new `email-filter-app` submodule. A third submodule `filter-lab` will be added in a
follow-up task — do not create it now.

This is a **structural move only** — no logic changes, no new features.

---

## Stack

- Java 21, Amazon Corretto toolchain
- Spring Boot 3.5.0
- Spring AI 1.1.4
- Gradle with Kotlin DSL
- No Lombok — plain Java throughout
- Records for immutable data carriers
- Constructor injection only — no @Autowired, no field injection
- Package by feature

---

## Current structure

```
AIEmailProcessingPlatform/
  build.gradle.kts
  settings.gradle.kts
  src/main/java/ca/aksentiev/emailfilter/
    ...all packages here...
  src/main/resources/
  src/test/...
```

---

## Target structure

```
AIEmailProcessingPlatform/
  settings.gradle.kts              ← updated to include submodules
  build.gradle.kts                 ← root conventions only, no app code
  email-filter-core/
    build.gradle.kts               ← plain java-library, NO Spring Boot plugin
    src/main/java/ca/aksentiev/emailfilter/
    src/test/...
  email-filter-app/
    build.gradle.kts               ← Spring Boot app, depends on email-filter-core
    src/main/java/ca/aksentiev/emailfilter/
    src/main/resources/
    src/test/...
```

---

## What belongs in `email-filter-core`

Move these packages wholesale — no code changes, just relocation:

- `email.imap` — `EmailMessage`, `ImapIdleMonitor`, IMAP infrastructure
- `filter` — `EmailFilter` interface, `FilterResult`, `FilterChainDispatcher`
- `filter.scoring` — scoring filter pipeline components
- `filter.spam` — `SpamFilter` and pipeline components
- `preprocessor` — `PreProcessorService` and related records
- `scoring` — `ScoringService`, `ScoringResult`, weight/threshold records
- `llm` — `LlmScoringService` (uses Spring AI `ChatClient`)
- `config` — `FiltersConfig`, `FilterDefinition`, and all `@ConfigurationProperties`
  classes that represent the domain model (filter rules, account config,
  thresholds, weights)

If any class in the above list has a hard dependency on Spring Boot autoconfiguration
(e.g. depends on `spring-boot-autoconfigure` directly), leave it in `email-filter-app`
and note it in a comment.

---

## What stays in `email-filter-app`

- Main application entry point (`@SpringBootApplication`)
- `ScanController`, `ScanService`, reload API endpoints
- `EmailActionService` (IMAP move/delete/flag actions)
- `SpamAssassinClient` (infrastructure)
- `application.yml`, `filters.yml`, `brands.json`, `char_substitutions.json`
- Any web layer or infrastructure beans not needed by core

---

## Gradle conventions

### settings.gradle.kts
```kotlin
rootProject.name = "AIEmailProcessingPlatform"
include(":email-filter-core", ":email-filter-app")
```

### Root build.gradle.kts
Shared plugin version declarations only — no subproject configuration here.
Use `plugins { } ` block with `apply false` for Boot and dependency management.

### email-filter-core/build.gradle.kts
```kotlin
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.4")
    }
}

dependencies {
    api("org.springframework.ai:spring-ai-ollama-spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-mail")
    api("org.springframework:spring-context")
    // add others as needed — keep spring-boot-autoconfigure out
}
```

### email-filter-app/build.gradle.kts
```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.4")
    }
}

dependencies {
    implementation(project(":email-filter-core"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    // add others currently in the root build.gradle.kts
}
```

**Do NOT add the Palantir Java Format plugin** — it is applied automatically via
project Gradle conventions.

---

## Spring AI usage — important

Spring AI version is **1.1.4**. The correct options class is `OllamaChatOptions`,
not `OllamaOptions`. Thinking suppression uses `ThinkingMode.DISABLED`:

```java
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaOptions.ThinkingMode;

OllamaChatOptions options = OllamaChatOptions.builder()
        .model(properties.ollama().model())
        .temperature(0.0)
        .thinkingMode(ThinkingMode.DISABLED)
        .build();
```

If any existing code uses `OllamaOptions`, update it to `OllamaChatOptions` during
the move. This is the only permitted logic change.

---

## Rules

- No Lombok anywhere
- No changes to business logic beyond the `OllamaOptions` → `OllamaChatOptions` fix
- Constructor injection throughout
- Records stay as records
- All existing tests must pass after the move
- `email-filter-core` must NOT depend on `spring-boot-autoconfigure`
- Update all import statements affected by package moves
- Do not create `filter-lab` module — that is a follow-up task
- Do not modify `Dockerfile` or `docker-compose.yml` until JAR output path is confirmed

---

## Verification steps

Run these in order after the restructure — all must pass before committing:

```bash
./gradlew :email-filter-core:build
./gradlew :email-filter-app:build
./gradlew :email-filter-app:bootJar
./gradlew test
```

---

## Commit message

```
Restructure as multi-module Gradle project

- Extract email-filter-core as plain Java library submodule
- email-filter-app depends on email-filter-core
- OllamaOptions → OllamaChatOptions with ThinkingMode.DISABLED (Spring AI 1.1.4)
- No other logic changes — structural move only
- All tests passing
```
