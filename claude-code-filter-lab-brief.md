# Claude Code Task: Add email-filter-lab Module to AIEmailProcessingPlatform

## Context

AIEmailProcessingPlatform is now a multi-module Gradle project:
```
AIEmailProcessingPlatform/
  settings.gradle.kts
  build.gradle.kts
  email-filter-core/       ← shared domain library
  email-filter-app/        ← production Spring Boot app (IMAP IDLE, live filtering)
```

This task adds a third module: `email-filter-lab` — a standalone Spring Boot CLI tool
for testing and validating filters against real emails in a specific IMAP folder before
promoting them to production.

**Do not modify email-filter-core or email-filter-app.**

---

## Purpose

Filter lab is a companion tool for iterating on filters. One session = one filter:

1. Configure one filter in `staging-filters.yml`
2. Point at an IMAP folder (e.g. `INBOX.LLM-Spam_Review`)
3. Run in dry-run mode — produces a scored report, no emails moved
4. Iterate on filter YAML + prompt until results look good
5. Promote manually — filter-lab prints the filter YAML block to copy into
   the main app's `filters.yml`
6. Reshuffle — re-run with `lab.dry-run=false`, filter-lab executes real actions
   on emails in the folder using the same filter

---

## Target structure

```
AIEmailProcessingPlatform/
  email-filter-lab/
    build.gradle.kts
    src/main/java/ca/aksentiev/emailfilter/lab/
      EmailFilterLabApplication.java
      LabRunner.java
      config/
        LabProperties.java
      report/
        LabReportPrinter.java
    src/main/resources/
      application.yml
    staging-filters.yml
```

---

## Build

### settings.gradle.kts change
```kotlin
include(":email-filter-core", ":email-filter-app", ":email-filter-lab")
```

### email-filter-lab/build.gradle.kts
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
    implementation("org.springframework.ai:spring-ai-ollama-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-mail")
}
```

Do NOT add Palantir Java Format plugin — applied automatically via project conventions.

---

## Stack and conventions

- Java 21, Spring Boot 3.5.0, Spring AI 1.1.4
- No Lombok
- Records for immutable data carriers
- Constructor injection only
- OllamaChatOptions (not OllamaOptions) with ThinkingMode.DISABLED
- spring.main.web-application-type=none — CLI only
- Package: ca.aksentiev.emailfilter.lab

---

## LabProperties

```java
@ConfigurationProperties(prefix = "lab")
public class LabProperties {

    private final Imap imap;
    private final Ollama ollama;
    private final Run run;

    public LabProperties(Imap imap, Ollama ollama, Run run) {
        this.imap = imap;
        this.ollama = ollama;
        this.run = run;
    }

    public Imap imap() { return imap; }
    public Ollama ollama() { return ollama; }
    public Run run() { return run; }

    public record Imap(
            String host,
            @DefaultValue("993") int port,
            @DefaultValue("true") boolean ssl,
            String username,
            String password,
            @DefaultValue("INBOX.LLM-Spam_Review") String folder) {}

    public record Ollama(
            @DefaultValue("http://192.168.10.158:11434") String baseUrl,
            @DefaultValue("qwen3.5:9b") String model,
            @DefaultValue("120") int timeoutSeconds) {}

    public record Run(
            String filter,
            @DefaultValue("0") int limit,
            @DefaultValue("true") boolean dryRun,
            String reportFile) {}
}
```

---

## application.yml

```yaml
spring:
  application:
    name: email-filter-lab
  main:
    web-application-type: none
  ai:
    ollama:
      base-url: ${OLLAMA_URL:http://192.168.10.158:11434}
      chat:
        options:
          model: ${OLLAMA_MODEL:qwen3.5:9b}
          temperature: 0.0

lab:
  imap:
    host: ${IMAP_HOST}
    port: ${IMAP_PORT:993}
    ssl: ${IMAP_SSL:true}
    username: ${IMAP_USERNAME}
    password: ${IMAP_PASSWORD}
    folder: ${IMAP_FOLDER:INBOX.LLM-Spam_Review}
  ollama:
    base-url: ${OLLAMA_URL:http://192.168.10.158:11434}
    model: ${OLLAMA_MODEL:qwen3.5:9b}
    timeout-seconds: 120
  run:
    filter: ${LAB_FILTER:spam-filter}
    limit: ${LIMIT:0}
    dry-run: ${DRY_RUN:true}
    report-file: ${REPORT_FILE:}

logging:
  level:
    ca.aksentiev: INFO
    org.springframework.ai: WARN
    org.springframework: WARN
```

---

## staging-filters.yml example

```yaml
filters:
  spam-filter:
    type: scoring
    enabled: true
    ollama-model: "qwen3.5:9b"
    whitelist:
      addresses: []
      domains: []
      patterns: []
    pre-processor:
      enabled: true
      brands-file: "brands.json"
      char-substitutions-file: "char_substitutions.json"
    spamassassin:
      enabled: false
      host: "spamassassin"
      port: 783
      skip-llm-above-score: 12.0
    scoring:
      weights:
        preprocessor: 0.20
        spamassassin: 0.35
        llm: 0.60
      thresholds:
        safe-max: 3
        review-max: 6
      actions:
        safe: leave
        review: move-to-junk
        spam: move-to-junk
    prompt: |
      You are an email classifier helping a Canadian software professional
      manage his inbox. Respond with valid JSON only:
      {"score": <1-10>, "reason": "<one sentence>"}
```

---

## LabRunner — ApplicationRunner

1. Load staging-filters.yml — bind to FiltersConfig from email-filter-core
2. Resolve named filter from lab.run.filter — exit with error if not found
3. Log session header: folder, filter name, type, dry-run status
4. Read emails via ImapFolderReader from email-filter-core
5. Apply limit if configured
6. For each email:
   a. Dispatch to ScoringFilter, ExtractionFilter, or LogisticsFilter by type
   b. Collect FilterResult
   c. Log progress inline: [01/47] Subject | Action: MOVE-TO-JUNK | Score: 9
7. Pass results to LabReportPrinter
8. If dry-run=false: execute real IMAP actions via EmailActionService
9. Print promote instructions
10. Shutdown via SpringApplication.exit

Dry-run: log actions with [DRY-RUN] prefix, do not execute.
Live: execute and log with [LIVE] prefix.

---

## LabReportPrinter

Per-email format varies by filter type.

Scoring filter:
```
[01/47]  Score:  9  [SPAM      ]
         Action : MOVE-TO-JUNK
         From   : bad@spam.com
         Subject: Free money!!!
         Reason : Suspicious domain impersonating PayPal
──────────────────────────────────────────────────────────────────────
```

Extraction filter:
```
[03/47]  Result : RESPONSE
         Action : SEND-EMAIL → paul@aksentiev.ca
         From   : linkedin@linkedin.com
         Subject: 5 new Director roles
         Extract: LinkedIn — Director of Engineering @ Shopify (Toronto)
──────────────────────────────────────────────────────────────────────
```

Logistics filter:
```
[05/47]  Rule   : subject-starts-with "[Job Alert]"
         Action : MOVE-TO-FOLDER → Job Alerts
         From   : linkedin@linkedin.com
         Subject: [Job Alert] LinkedIn — new matches
──────────────────────────────────────────────────────────────────────
```

Summary (all filter types):
```
  ACTIONS SUMMARY
  leave             :  9
  move-to-junk      : 35
  move-to-folder    :  3
  send-email        :  0
  errors            :  0
```

Scoring filters also show score distribution:
```
  SCORE DISTRIBUTION
  1-3  (legitimate) : 12
  4-7  (borderline) :  3
  8-10 (spam)       : 32
```

Promote block — always printed at end:
```
══════════════════════════════════════════════════════════════════════
  PROMOTE THIS FILTER
  Copy the block below into the main app's filters.yml, then run:
  curl -X POST http://192.168.10.180:8081/api/reload/filters
══════════════════════════════════════════════════════════════════════

spam-filter:
  type: scoring
  ... (full YAML serialized from the loaded filter config via SnakeYAML)
```

Use SnakeYAML (already on classpath via Spring Boot) to serialize the filter
definition back to YAML for the promote block.

---

## Spring AI

```java
OllamaChatOptions options = OllamaChatOptions.builder()
        .model(properties.ollama().model())
        .temperature(0.0)
        .thinkingMode(ThinkingMode.DISABLED)
        .build();
```

---

## Verification

```bash
./gradlew :email-filter-lab:build
./gradlew :email-filter-core:build
./gradlew :email-filter-app:build
```

All three must pass. No changes to existing modules.

---

## Commit message

```
Add email-filter-lab module

- Standalone Spring Boot CLI companion tool for filter testing
- Supports all three filter types: scoring, extraction, logistics
- Dry-run by default — report only, no emails moved
- Live mode executes real IMAP actions (lab.run.dry-run=false)
- Prints promote instructions + YAML block at end of every session
- Depends on email-filter-core; no changes to existing modules
```
