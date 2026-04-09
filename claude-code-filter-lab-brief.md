# Claude Code Task: Add email-filter-lab Module

## Branch
Work on branch: `feature/new-filter-engine`

## Context

AIEmailProcessingPlatform is a multi-module Gradle project:
```
AIEmailProcessingPlatform/
  settings.gradle.kts
  build.gradle.kts
  email-filter-core/       ← shared domain library (EmailMessage, ImapFolderReader, etc.)
  email-filter-app/        ← production Spring Boot app (IMAP IDLE, live filtering)
```

This task adds a third module: `email-filter-lab` — a standalone Spring Boot CLI tool
for designing, testing, and validating the new filter engine against real emails before
promoting to production.

**IMPORTANT:**
- Do not modify email-filter-core or email-filter-app
- The new filter engine (ExtractionFilter, LogisticsFilter, FilterChainDispatcher, etc.)
  is implemented DIRECTLY in email-filter-lab for now — NOT in email-filter-core
- This is intentional: validate the design in the lab first, extract to core later
- email-filter-lab depends on email-filter-core only for: EmailMessage, ImapFolderReader
  and any existing shared records/interfaces already there
- All new filter engine classes live under ca.aksentiev.emailfilter.lab.engine

---

## Purpose

One session = one filter:

1. Configure one filter in staging-filters.yml
2. Point at an IMAP folder
3. Run dry-run — produces scored report, no emails moved
4. Iterate on filter YAML + prompt, re-run, compare
5. When satisfied — promote block printed for manual copy to main app's filters.yml
6. Reshuffle — re-run with lab.run.dry-run=false, executes real IMAP actions

---

## Target structure

```
AIEmailProcessingPlatform/
  email-filter-lab/
    build.gradle.kts
    staging-filters.yml                        ← working example, all three filter types
    src/main/java/ca/aksentiev/emailfilter/lab/
      EmailFilterLabApplication.java
      LabRunner.java                            ← ApplicationRunner, orchestrates session
      config/
        LabProperties.java                      ← @ConfigurationProperties(prefix="lab")
        LabFiltersConfig.java                   ← binds staging-filters.yml
      engine/
        ─── config model ───
        FilterDefinition.java                   ← record: all fields for any filter type
        FilterType.java                         ← enum: SCORING, EXTRACTION, LOGISTICS
        WhitelistConfig.java                    ← record: addresses, domains, patterns
        PreProcessorConfig.java                 ← record: enabled, brands-file, etc.
        SpamAssassinConfig.java                 ← record: enabled, host, port, skip-above
        ScoringConfig.java                      ← record: weights, thresholds, actions
        ScoringWeights.java                     ← record: preprocessor, spamassassin, llm
        ScoringThresholds.java                  ← record: safe-max, review-max
        ScoringActions.java                     ← record: safe, review, spam (action names)
        ExtractionActions.java                  ← record: on-response list, always list
        ActionDefinition.java                   ← record: type, folder, to, subject, body
        LogisticsRule.java                      ← record: condition, list of actions
        Condition.java                          ← record: single/all-of/any-of/not
        ─── results ───
        FilterResult.java                       ← record: action, targetFolder, score,
                                                          reason, llmResponse, ruleName
        ActionType.java                         ← enum: LEAVE, FLAG, MOVE_TO_JUNK,
                                                          MOVE_TO_FOLDER, DELETE, SEND_EMAIL
        ─── execution ───
        WhitelistMatcher.java                   ← checks email against whitelist config
        VariableResolver.java                   ← substitutes {variable} tokens
        ConditionEvaluator.java                 ← evaluates Condition against EmailMessage
        ScoringFilter.java                      ← executes scoring-type filters
        ExtractionFilter.java                   ← executes extraction-type filters
        LogisticsFilter.java                    ← executes logistics-type filters
        FilterDispatcher.java                   ← dispatches to correct filter by type
        ─── actions ───
        ActionExecutor.java                     ← executes FilterResult actions on IMAP
        SmtpEmailSender.java                    ← sends outbound email via JavaMailSender
      report/
        LabReportPrinter.java                   ← formats and prints report + promote block
    src/main/resources/
      application.yml
```

---

## settings.gradle.kts

Add email-filter-lab:
```kotlin
include(":email-filter-core", ":email-filter-app", ":email-filter-lab")
```

---

## email-filter-lab/build.gradle.kts

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

## Conventions

- Java 21, Spring Boot 3.5.0, Spring AI 1.1.4
- No Lombok
- Records for all immutable data carriers (config model, results)
- Constructor injection only — no @Autowired, no field injection
- OllamaChatOptions (NOT OllamaOptions) with ThinkingMode.DISABLED
- spring.main.web-application-type=none — CLI only, no web server
- Base package: ca.aksentiev.emailfilter.lab

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
  mail:
    host: ${SMTP_HOST:smtp.gmail.com}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME:}
    password: ${SMTP_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

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
  smtp:
    from-address: ${SMTP_FROM_ADDRESS:${SMTP_USERNAME:}}
    from-name: "AI Email Filter Lab"
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

## LabProperties — @ConfigurationProperties(prefix = "lab")

```java
@ConfigurationProperties(prefix = "lab")
public class LabProperties {

    private final Imap imap;
    private final Ollama ollama;
    private final Smtp smtp;
    private final Run run;

    // constructor, getters

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

    public record Smtp(
            @DefaultValue("") String fromAddress,
            @DefaultValue("AI Email Filter Lab") String fromName) {}

    public record Run(
            @DefaultValue("spam-filter") String filter,
            @DefaultValue("0") int limit,
            @DefaultValue("true") boolean dryRun,
            String reportFile) {}
}
```

---

## LabFiltersConfig — loads staging-filters.yml

Load staging-filters.yml as a separate config file, not via Spring's default
application.yml loading. Use a @Bean that reads the file explicitly via
SnakeYAML and binds to a Map<String, FilterDefinition>.

The file is expected at the working directory (project root when run from IntelliJ).
Fall back to classpath if not found on disk.

---

## Engine — Config model records

### FilterDefinition
All fields optional except type. Unset fields are null.

```java
public record FilterDefinition(
        FilterType type,             // defaults to SCORING if absent
        boolean enabled,
        String ollamaModel,
        WhitelistConfig whitelist,
        PreProcessorConfig preProcessor,
        SpamAssassinConfig spamAssassin,
        ScoringConfig scoring,
        Map<String, String> sourceDomains,   // domain → display name
        Map<String, Object> data,            // named lists for variable injection
        String prompt,
        ExtractionActions actions,           // extraction: on-response + always
        List<LogisticsRule> rules)           // logistics rules
```

### ActionDefinition
```java
public record ActionDefinition(
        ActionType type,
        String folder,    // move-to-folder
        String to,        // send-email
        String subject,   // send-email
        String body)      // send-email
```

### LogisticsRule
```java
public record LogisticsRule(
        Condition condition,
        List<ActionDefinition> actions)
```

### Condition
Two-level boolean composition. No deeper nesting required.

```java
public record Condition(
        // bare shorthand — single condition key/value
        String subjectStartsWith,
        String subjectEndsWith,
        String subjectContains,
        String fromDomain,
        String fromAddress,
        String fromAddressContains,
        // boolean compositions
        List<Condition> allOf,
        List<Condition> anyOf,
        Condition not)
```

### FilterResult
```java
public record FilterResult(
        ActionType action,
        String targetFolder,    // for MOVE_TO_FOLDER
        String emailTo,         // for SEND_EMAIL
        String emailSubject,    // for SEND_EMAIL
        String emailBody,       // for SEND_EMAIL
        int score,              // scoring filters only
        String reason,          // scoring filters only
        String llmResponse,     // extraction filters only
        String matchedRule)     // logistics filters only
```

ActionType.LEAVE and ActionType.FLAG are non-destructive (do not stop chain).
All others are destructive.

---

## Engine — Execution

### WhitelistMatcher

Checks email against whitelist config. Exact address match, exact domain match,
and wildcard pattern match (only `*` wildcard supported, e.g. `*@trusted.com`).

Returns true if the email sender matches any entry.

### VariableResolver

Substitutes {variable} tokens in strings. Available variables:

Email variables (all filter types):
- {email.from}, {email.subject}, {email.body}, {email.date}, {email.to}

Source variables (extraction with source-domains):
- {source} — display name from source-domains map
- {source.domain} — matched domain

LLM response (extraction actions only):
- {llm.response}

Data variables (extraction):
- Any key from the filter's data map, lists formatted as comma-separated values

Scoring variables (scoring prompt only):
- {preprocessor.findings}, {preprocessor.score}
- {spamassassin.score}, {spamassassin.raw-score}

### ConditionEvaluator

Evaluates a Condition against an EmailMessage. Supports:
- Bare shorthand: single condition field populated
- all-of: all sub-conditions must match
- any-of: at least one sub-condition must match
- not: inner condition must NOT match

All string comparisons case-insensitive.

Condition types:
- subject-starts-with: prefix match on subject
- subject-ends-with: suffix match on subject
- subject-contains: substring match on subject
- from-domain: sender address contains this domain
- from-address: exact match on sender address
- from-address-contains: substring match on sender address

### ScoringFilter

Executes scoring-type filter against an EmailMessage.

Steps:
1. Check whitelist → if matched, return FilterResult(LEAVE)
2. Run pre-processor if enabled → produce findings string and score
3. Run SpamAssassin if enabled → produce normalized score 0-10
   - If SA raw score >= skip-llm-above-score → skip LLM, use SA score
4. Call LLM with resolved prompt (variable substitution applied)
   - Use OllamaChatOptions with ThinkingMode.DISABLED, temperature 0.0
   - Parse {"score": N, "reason": "..."} from response
   - Strip <think>...</think> blocks before parsing
   - On parse failure: score = 7, reason = "Parse failure — treating as suspicious"
5. Calculate weighted average across available layers
   - Auto-adjust weights proportionally if a layer is unavailable
6. Map weighted score to threshold:
   - score <= safe-max → action from scoring.actions.safe
   - score <= review-max → action from scoring.actions.review
   - else → action from scoring.actions.spam
7. Build subject tag: [PP:3/SA:8/LLM:9=8] — include only layers that ran
8. Return FilterResult with action, score, reason

For pre-processor: reuse PreProcessorService from email-filter-core if it exists
there, otherwise implement a minimal stub that returns empty findings and score 1
(the lab focuses on filter design, not pre-processor accuracy).

For SpamAssassin: default disabled in staging-filters.yml. Implement
SpamAssassinClient stub that returns score 0 when disabled.

### ExtractionFilter

Executes extraction-type filter against an EmailMessage.

Steps:
1. Check whitelist → if matched, return FilterResult(LEAVE)
2. If source-domains configured: check sender domain
   - Extract domain from sender address
   - If no match → return FilterResult(LEAVE) without LLM call
   - If match → resolve {source} and {source.domain} variables
3. Resolve all variables in prompt (email, source, data variables)
4. Truncate {email.body} to 8000 chars if needed
5. Call LLM with resolved prompt
   - OllamaChatOptions with ThinkingMode.DISABLED, temperature 0.0
   - Strip <think>...</think> blocks from response
6. Trim response
7. If response equals "NONE" (case-insensitive):
   - Execute always actions only
   - Return FilterResult with first always action
8. Otherwise:
   - Resolve {llm.response} variable in all on-response action fields
   - Execute on-response actions, then always actions
   - Return FilterResult with first destructive action found, or LEAVE

Multiple actions in on-response/always: ActionExecutor executes all of them.
FilterResult carries the first destructive action for chain-stop semantics.

### LogisticsFilter

Executes logistics-type filter against an EmailMessage. No LLM call.

Steps:
1. Iterate rules in declaration order
2. For each rule: evaluate condition via ConditionEvaluator
3. On first match: execute all actions for that rule
4. Return FilterResult with first destructive action, or LEAVE if none
5. If no rule matches: return FilterResult(LEAVE)

### FilterDispatcher

Dispatches to ScoringFilter, ExtractionFilter, or LogisticsFilter based on
FilterDefinition.type(). Handles disabled filters (return LEAVE immediately).

### ActionExecutor

Executes actions described in a FilterResult against the live IMAP session.

Supported actions:
- LEAVE: no-op
- FLAG: set FLAGGED flag on message
- MOVE_TO_JUNK: move to account's junk/spam folder
- MOVE_TO_FOLDER: create folder if absent, move message
- DELETE: expunge message (only when dry-run=false)
- SEND_EMAIL: send via SmtpEmailSender

In dry-run mode: log all actions with [DRY-RUN] prefix, do not execute.
In live mode: execute and log with [LIVE] prefix.

### SmtpEmailSender

Sends outbound email via Spring's JavaMailSender.
Uses lab.smtp.from-address and lab.smtp.from-name from LabProperties.

---

## LabRunner — ApplicationRunner

```
1. Load staging-filters.yml via LabFiltersConfig
2. Resolve named filter (lab.run.filter) — exit with clear error if not found
3. Log session header:
   ═══════════════════════════════════════════════
     Email Filter Lab
     Branch : feature/new-filter-engine
     Filter : spam-filter [SCORING]
     Folder : INBOX.LLM-Spam_Review
     Model  : qwen3.5:9b @ http://192.168.10.158:11434
     Mode   : DRY-RUN
   ═══════════════════════════════════════════════
4. Read emails via ImapFolderReader from email-filter-core
5. Apply limit if configured
6. For each email:
   a. Dispatch via FilterDispatcher
   b. Collect FilterResult
   c. Log progress: [01/47] "Subject truncated..." → MOVE-TO-JUNK (score: 9)
7. Pass all results + emails to LabReportPrinter
8. If dry-run=false: execute IMAP actions via ActionExecutor
9. Print promote instructions
10. Shutdown via SpringApplication.exit(applicationContext, () -> 0)
```

---

## LabReportPrinter

Per-email format by filter type:

SCORING:
```
[01/47]  Score:  9  [SPAM      ]
         Action : MOVE-TO-JUNK
         From   : bad@rfmvqu.com
         Subject: Tax Compliance Record Renewal Notice
         Reason : Suspicious domain impersonating TD Direct Investing
──────────────────────────────────────────────────────────────────────
```

EXTRACTION (LLM returned content):
```
[03/47]  Result : RESPONSE
         Action : SEND-EMAIL → paul@aksentiev.ca | MOVE-TO-FOLDER → Job Search Emails Processed
         From   : jobs@linkedin.com
         Subject: 5 new Director roles matching your profile
         Extract: LinkedIn — 5 new Director roles...
──────────────────────────────────────────────────────────────────────
```

EXTRACTION (LLM returned NONE):
```
[04/47]  Result : NONE
         Action : MOVE-TO-FOLDER → Job Search Emails Processed
         From   : newsletter@substack.com
         Subject: Weekly digest
──────────────────────────────────────────────────────────────────────
```

LOGISTICS:
```
[05/47]  Rule   : subject-starts-with "[Job Alert]"
         Action : MOVE-TO-FOLDER → Job Alerts
         From   : jobs@linkedin.com
         Subject: [Job Alert] LinkedIn — new matches
──────────────────────────────────────────────────────────────────────
```

LEAVE (whitelisted or no match):
```
[06/47]  Result : LEAVE (whitelisted)
         From   : paul@aksentyev.net
         Subject: Re: Introduction email
──────────────────────────────────────────────────────────────────────
```

Summary section (always shown):
```
══════════════════════════════════════════════════════════════════════
  SUMMARY — spam-filter [SCORING] — INBOX.LLM-Spam_Review
  Emails processed : 47
══════════════════════════════════════════════════════════════════════

  SCORE DISTRIBUTION          ACTIONS
  1-3  (legitimate) : 12      leave          :  9
  4-7  (borderline) :  3      move-to-junk   : 35
  8-10 (spam)       : 32      move-to-folder :  3
  errors            :  0      send-email     :  0
                              flag           :  0
```

(Score distribution shown for scoring filters only.)

Promote block — always printed at end, regardless of dry-run:
```
══════════════════════════════════════════════════════════════════════
  PROMOTE THIS FILTER
  1. Copy the YAML block below into the main app's filters.yml
  2. Add filter name to the account's filter list
  3. Run: curl -X POST http://192.168.10.180:8081/api/reload/filters
══════════════════════════════════════════════════════════════════════

  spam-filter:
    type: scoring
    enabled: true
    ollama-model: qwen3.5:9b
    ... (full YAML of the filter as loaded, serialized via SnakeYAML)
```

Use SnakeYAML (already on classpath via Spring Boot) to serialize the
FilterDefinition back to YAML for the promote block.
Indent the block with 2 spaces for readability.

Write report to file if lab.run.report-file is set.

---

## staging-filters.yml — working example

Provide all three filter types so the lab can be tested immediately.
The active filter is selected via lab.run.filter in application.yml.

```yaml
filters:

  # ── Scoring ──────────────────────────────────────────────────────────────────

  spam-filter:
    type: scoring
    enabled: true
    ollama-model: "qwen3.5:9b"
    whitelist:
      addresses: []
      domains:
        - "aksentiev.ca"
        - "aksentyev.net"
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
        spamassassin: 0.05
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
      manage his inbox. Your job is to distinguish genuinely useful email
      from unwanted spam.

      Most email is legitimate. Only score high if there is clear evidence
      of deception, impersonation, or unsolicited commercial intent with
      no prior relationship.

      Score guide:
        1-2  Definitely legitimate — expected, wanted, or ongoing conversation
        3-4  Probably legitimate — promotional but from a trusted sender
        5-6  Borderline — unsolicited but not clearly deceptive
        7-8  Likely spam — manipulative language or impersonation signals
        9-10 Clear spam — brand impersonation, character substitution, phishing

      IMPORTANT: If the email contains quoted reply history (lines starting
      with ">" or "On [date] ... wrote:"), this is an ongoing conversation
      — score 1-3 unless the original sender shows clear deception.

      From: {email.from}
      Subject: {email.subject}

      {email.body}

      Return ONLY valid JSON:
      {"score": <1-10>, "reason": "<one sentence explanation>"}

  # ── Extraction ───────────────────────────────────────────────────────────────

  job-search-filter:
    type: extraction
    enabled: true
    ollama-model: "qwen3.5:9b"
    source-domains:
      linkedin.com: "LinkedIn"
      indeed.com: "Indeed"
      glassdoor.com: "Glassdoor"
      ziprecruiter.com: "ZipRecruiter"
      workopolis.com: "Workopolis"
      jobbank.gc.ca: "Job Bank Canada"
    data:
      levels:
        - "Director"
        - "Sr. Director"
        - "Senior Director"
        - "VP"
        - "Vice President"
        - "Manager"
        - "Senior Manager"
        - "Head of"
      domains:
        - "Software"
        - "Software Engineering"
        - "Engineering"
        - "Development"
        - "Technology"
        - "Platform"
    prompt: |
      You are analyzing a job notification email from {source}.
      Find all positions where the title level is one of: {levels}
      AND the domain relates to one of: {domains}.

      If matches are found, format the response as:

      {source} — {email.subject}

      • {title} @ {company} ({location})
        {link}

      If NO positions match, respond with exactly: NONE
    actions:
      on-response:
        - type: send-email
          to: "${JOB_NOTIFY_ADDRESS}"
          subject: "[Job Alert] {source} — {email.subject}"
          body: "{llm.response}"
      always:
        - type: move-to-folder
          folder: "Job Search Emails Processed"

  # ── Logistics ────────────────────────────────────────────────────────────────

  logistics-filter:
    type: logistics
    enabled: true
    rules:
      - condition:
          subject-starts-with: "[Job Alert]"
        actions:
          - type: move-to-folder
            folder: "Job Alerts"
      - condition:
          subject-starts-with: "[LLM Summary]"
        actions:
          - type: move-to-folder
            folder: "Summaries"
      - condition:
          any-of:
            - subject-starts-with: "[FLYER"
            - subject-starts-with: "[SALE"
        actions:
          - type: flag
          - type: move-to-folder
            folder: "Deals"
      - condition:
          from-domain: "linkedin.com"
        actions:
          - type: move-to-folder
            folder: "Job Search Emails Processed"
```

---

## Spring AI usage

```java
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaOptions.ThinkingMode;

OllamaChatOptions options = OllamaChatOptions.builder()
        .model(filterDefinition.ollamaModel())
        .temperature(0.0)
        .thinkingMode(ThinkingMode.DISABLED)
        .build();

String response = chatClient
        .prompt(resolvedPrompt)
        .options(options)
        .call()
        .content();
```

---

## Error handling

- No silent exception swallowing
- Log and continue on per-email errors — one bad email should not abort the session
- LLM parse failure: score = 7, reason = "Parse failure — treating as suspicious"
- IMAP action failure in live mode: log error with email subject, continue
- Missing required config (IMAP_HOST etc.): fail fast with clear message at startup

---

## .gitignore

Ensure root .gitignore contains:
```
application-local.yml
```

---

## Verification

```bash
./gradlew :email-filter-lab:build
./gradlew :email-filter-core:build
./gradlew :email-filter-app:build
```

All three must pass. No changes to email-filter-core or email-filter-app.

---

## Commit message

```
Add email-filter-lab module (feature/new-filter-engine)

- Standalone Spring Boot CLI companion tool for filter design and validation
- Full new filter engine implemented in lab: ScoringFilter, ExtractionFilter,
  LogisticsFilter, FilterDispatcher, ConditionEvaluator, ActionExecutor,
  VariableResolver, WhitelistMatcher, SmtpEmailSender
- Supports all three filter types: scoring, extraction, logistics
- Dry-run by default — report only, no emails moved
- Live mode executes real IMAP actions (DRY_RUN=false)
- staging-filters.yml with working examples of all three filter types
- Promote block printed at end of every session
- No changes to email-filter-core or email-filter-app
```
