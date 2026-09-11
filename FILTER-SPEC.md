# AI Email Filter — Filter Specification

## Overview

The filter chain is the core processing unit of the platform. Each email passes through
a configured sequence of filters. Filters are defined entirely in `filters.yml` — adding
a new filter requires no Java code, only YAML configuration.

There are three filter types:

| Type | LLM Call | Primary Use Case |
|---|---|---|
| `scoring` | Yes — returns numeric score | Spam detection, content quality |
| `extraction` | Yes — returns formatted text or `NONE` | Job alerts, newsletter summaries, flyer parsing |
| `logistics` | No | Subject-prefix routing, folder organisation, flag rules |

---

## Execution Model

### Filter Chain

Each email account declares an ordered list of filters. Filters execute sequentially
in declaration order. The chain uses **stop-on-first-destructive-action** semantics:
once a filter produces a destructive action (`move-to-folder`, `move-to-junk`,
`delete`), the chain stops. Non-destructive actions (`flag`, `leave`) do not stop
the chain.

```yaml
accounts:
  - name: "Personal"
    host: "imap.example.com"
    username: "${IMAP_USERNAME_1}"
    password: "${IMAP_PASSWORD_1}"
    filters:
      - logistics-filter       # runs first — routes already-tagged subjects fast
      - spam-filter            # scoring filter
      - job-search-filter      # extraction filter
      - newsletter-filter      # extraction filter
```

**Ordering matters.** `logistics-filter` should generally run first so that emails
already tagged by a previous cycle (e.g. a `[Job Alert]` subject prefix written in
a prior run) are routed without triggering unnecessary LLM calls.

### Dry-Run Mode

When `dry-run.enabled: true` (default), no actions are executed. All decisions are
logged with `[DRY-RUN]` prefix. The Scan API always forces dry-run regardless of
config, as a safety mechanism.

### Whitelist

Each filter supports a per-filter whitelist. Whitelisted emails skip that filter and
pass through with action `LEAVE`. Whitelisting is intentionally scoped per-filter —
a whitelisted sender for the spam-filter still passes through the job-search-filter.

```yaml
filters:
  spam-filter:
    whitelist:
      addresses:
        - "boss@company.com"
      domains:
        - "company.com"
        - "regex:(?:[a-z0-9-]+\\.)?(?:gov|gc)\\.ca"
      patterns:
        - "*@trusted-partner.com"
        - "regex:.*@(?:[a-z0-9-]+\\.)?(?:gov|gc)\\.ca"
```

`domains` entries are exact (case-insensitive) matches against the sender's domain by
default. Prefix an entry with `regex:` to compile the remainder as a raw Java regex
instead, matched against just the domain portion (`Matcher.matches()`) — e.g.
`regex:(?:[a-z0-9-]+\.)?(?:gov|gc)\.ca` matches `gov.ca`, `health.gov.ca`, and
`cra.gc.ca`.

`patterns` entries are glob wildcards (`*` = any chars, `?` = any one char) by default.
Prefix an entry with `regex:` to compile the remainder as a raw Java regex instead —
needed for alternation, character classes, or anything glob syntax can't express.
Regexes are matched against the full lowercased sender address (`Matcher.matches()`),
same as wildcards.

---

## Filter Type: `scoring`

Uses the three-layer pipeline (pre-processor → SpamAssassin → LLM). The LLM returns
a numeric score. Actions are driven by score thresholds.

### Full YAML Shape

```yaml
filters:
  spam-filter:
    type: scoring                        # explicit; also the default if type is omitted
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
      enabled: true
      host: "spamassassin"
      port: 783
      skip-llm-above-score: 12.0         # obvious spam — skip LLM entirely

    scoring:
      weights:
        preprocessor: 0.20
        spamassassin: 0.35
        llm: 0.45
      # Weights auto-adjust proportionally if a layer is unavailable (graceful degradation).
      # e.g. if SpamAssassin is down: preprocessor=0.31, llm=0.69

      thresholds:
        safe-max: 3                      # score 1–3 → safe
        review-max: 6                    # score 4–6 → review; 7–10 → spam

      actions:
        safe:   leave
        review: move-to-junk
        spam:   move-to-junk

    prompt: |
      You are a spam detection engine. Analyze this email and return a spam score.

      Pre-processor findings:
      {preprocessor.findings}

      SpamAssassin score: {spamassassin.score} / 10

      Email:
      From: {email.from}
      Subject: {email.subject}
      Body:
      {email.body}

      Return ONLY valid JSON: {"score": <1-10>, "reason": "<brief explanation>"}
      Score 1 = definitely legitimate. Score 10 = definitely spam.
```

### Scoring Filter Execution

1. Check whitelist → if matched, return `LEAVE`
2. Run pre-processor → produce findings
3. Run SpamAssassin → produce normalized score (0–10)
4. If SA raw score ≥ `skip-llm-above-score` → skip LLM, use SA score directly
5. Run LLM with enriched prompt → parse `{"score": N, "reason": "..."}`
6. Calculate weighted average across available layers
7. Map score to threshold → determine action
8. Add X-headers to email: `X-AIFilter-Score`, `X-AIFilter-Reason`, `X-AIFilter-Layers`
9. Tag subject if action is `move-to-junk` or `move-to-folder`: `[PP:3/SA:8/LLM:9=8]`
10. Execute action

---

## Filter Type: `extraction`

Calls the LLM with a user-defined prompt. The LLM either returns formatted content
or the sentinel value `NONE`. No scoring, no thresholds. Actions split into
`on-response` (LLM returned something useful) and `always` (runs regardless).

The key principle: **the LLM response IS the email body for any outbound notification**.
No separate compose step. The prompt does both extraction and formatting in one call.

### Full YAML Shape

```yaml
filters:
  job-search-filter:
    type: extraction
    enabled: true
    ollama-model: "qwen3.5:9b"

    whitelist:
      addresses: []
      domains: []
      patterns: []

    # Optional: restrict LLM processing to emails matching these gates.
    # Both gates are OR'd — a match on either lets the email through.
    # If neither gate matches, the filter returns LEAVE without an LLM call.
    # Omit both to process all emails.

    # Gate 1: match on sender domain (extracted from the From address)
    source-domains:
      linkedin.com: "LinkedIn"
      indeed.com: "Indeed"
      glassdoor.com: "Glassdoor"
      ziprecruiter.com: "ZipRecruiter"
      monster.com: "Monster"
      careerbuilder.com: "CareerBuilder"
      workopolis.com: "Workopolis"
      jobbank.gc.ca: "Job Bank Canada"

    # Gate 2: match on subject line (case-insensitive starts-with).
    # Useful for senders on generic recruitment platforms (e.g. jobs2web.com)
    # where the sender domain is shared across many organisations.
    source-subjects:
      "New jobs posted by Canadian Blood Services": "Canadian Blood Services"

    # data: named lists injected into the prompt via {variable} substitution
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
      Find all positions where:
        - Title level is one of: {levels}
        - Domain relates to one of: {domains}

      If matching positions are found, format the response as:

      {source} — {email.subject}

      • {title} @ {company} ({location})
        {link}

      If NO positions match the criteria, respond with exactly: NONE

    actions:
      on-response:                       # fires when LLM returns anything other than NONE
        - type: send-email
          to: "${JOB_NOTIFY_ADDRESS}"
          subject: "[Job Alert] {source} — {email.subject}"
          body: "{llm.response}"
        - type: move-to-folder
          folder: "Jobs/Job Search Emails/Processed"

      always:                            # fires regardless of LLM response
        - type: delete                   # no matching jobs → delete the digest email
```

```yaml
filters:
  newsletter-filter:
    type: extraction
    enabled: true
    ollama-model: "qwen3.5:9b"

    source-domains:
      substack.com: "Substack"
      beehiiv.com: "Beehiiv"
      mailchimp.com: "Mailchimp"
      convertkit.com: "ConvertKit"

    prompt: |
      Summarize this newsletter in 5 concise bullet points.
      Focus on the key information, announcements, or insights.
      Plain text only. No preamble. No sign-off.

      If this email is NOT a newsletter, respond with exactly: NONE

    actions:
      on-response:
        - type: send-email
          to: "${SELF_EMAIL}"
          subject: "[LLM Summary] {email.subject}"
          body: "{llm.response}"
      always:
        - type: move-to-folder
          folder: "Newsletters"
```

```yaml
filters:
  flyer-filter:
    type: extraction
    enabled: true
    ollama-model: "qwen3.5:9b"

    source-domains:
      costco.com: "Costco"
      canadiantire.ca: "Canadian Tire"
      bestbuy.ca: "Best Buy"
      staples.ca: "Staples"

    data:
      items-of-interest:
        - "coffee maker"
        - "air fryer"
        - "NVMe SSD"
        - "GPU"
        - "monitor"
        - "wireless headphones"

    prompt: |
      You are analyzing a promotional flyer email from {source}.
      Check if any of these items are on sale or featured: {items-of-interest}

      If matches are found, list each matched item with its price and any relevant details.
      Format: • {item}: {price} — {brief detail}

      If NONE of the items appear, respond with exactly: NONE

    actions:
      on-response:
        - type: send-email
          to: "${SELF_EMAIL}"
          subject: "[FLYER] {source} — items matched"
          body: "{llm.response}"
        - type: flag
      always:
        - type: move-to-folder
          folder: "Flyers"
```

### Extraction Filter Execution

1. Check whitelist → if matched, return `LEAVE`
2. Check source gates (if configured):
   a. Check sender domain against `source-domains` → if match, set `{source}` from map value
   b. If no domain match, check subject against `source-subjects` (case-insensitive starts-with) → if match, set `{source}` from map value
   c. If neither gate matches → return `LEAVE` without LLM call
   d. If neither gate is configured → proceed; resolve `{source}` from sender address
3. Inject all `data` variables and standard variables into prompt (see Variable Reference)
4. Truncate `{email.body}` to 8000 characters if necessary — digest emails can be very large
5. Call LLM with temperature `0.0`
6. Trim response
7. If response equals `NONE` (case-insensitive) → execute `always` actions only
8. Otherwise → execute `on-response` actions, then `always` actions
9. Variable `{llm.response}` in action parameters is substituted with the LLM output

---

## Filter Type: `logistics`

No LLM call. Evaluates a list of rules top to bottom. Each rule has a condition and
one or more actions. **Stop on first matching rule** — subsequent rules are not
evaluated once a match is found.

Designed for routing emails by subject prefix, sender domain, or other header
attributes — particularly useful for routing emails that were tagged by extraction
filters in the same or a previous processing cycle.

### Full YAML Shape

```yaml
filters:
  logistics-filter:
    type: logistics
    enabled: true

    # Rules evaluated top to bottom. First match wins.
    rules:

      # Single condition shorthand (no boolean wrapper needed)
      - condition:
          subject-starts-with: "[Job Alert]"
        actions:
          - type: move-to-folder
            folder: "Job Alerts"

      # AND — all conditions must be true
      - condition:
          all-of:
            - subject-starts-with: "[Job Alert]"
            - from-domain: "linkedin.com"
        actions:
          - type: move-to-folder
            folder: "Job Alerts/LinkedIn"

      # OR — any condition must be true
      - condition:
          any-of:
            - subject-starts-with: "[FLYER"
            - subject-starts-with: "[SALE"
        actions:
          - type: move-to-folder
            folder: "Deals"
          - type: flag

      - condition:
          subject-starts-with: "[LLM Summary]"
        actions:
          - type: move-to-folder
            folder: "Summaries"

      - condition:
          from-domain: "linkedin.com"
        actions:
          - type: move-to-folder
            folder: "Job Search Emails Processed"

      - condition:
          from-address: "no-reply@github.com"
        actions:
          - type: move-to-folder
            folder: "GitHub"

      - condition:
          subject-contains: "invoice"
        actions:
          - type: flag
          - type: move-to-folder
            folder: "Finance"
```

### Condition Types

| Condition Key | Match Logic |
|---|---|
| `subject-starts-with` | Case-insensitive prefix match on subject |
| `subject-ends-with` | Case-insensitive suffix match on subject |
| `subject-contains` | Case-insensitive substring match on subject |
| `from-domain` | Sender address contains this domain |
| `from-address` | Exact match on sender address (case-insensitive) |
| `from-address-contains` | Substring match on sender address |

### Condition Composition

Two-level boolean composition. No deeper nesting required.

```yaml
# Bare shorthand — single condition, no wrapper
condition:
  subject-starts-with: "[Job Alert]"

# AND
condition:
  all-of:
    - subject-starts-with: "[Job Alert]"
    - from-domain: "linkedin.com"

# OR
condition:
  any-of:
    - from-domain: "linkedin.com"
    - from-domain: "indeed.com"

# NOT — negates the inner condition
condition:
  not:
    from-domain: "trusted.com"
```

Multiple actions within a rule all execute — `move-to-folder` and `flag` both fire.
The rule stops the chain if any of its actions is destructive.

### Logistics Filter Execution

1. Iterate rules in declaration order
2. For each rule, evaluate condition against the email
3. On first match: execute all actions for that rule → stop evaluating further rules
4. If no rule matches: return `LEAVE`

---

## Action Catalogue

All action types available to all filter types (where contextually applicable).

### `leave`
Do nothing. Pass email through unchanged. Non-destructive — does not stop the chain.

```yaml
- type: leave
```

### `flag`
Flag/star the email in the mailbox. Non-destructive — does not stop the chain.
Can be combined with other actions in the same rule.

```yaml
- type: flag
```

### `move-to-junk`
Move email to the account's configured junk/spam folder. Destructive — stops the chain.

```yaml
- type: move-to-junk
```

### `move-to-folder`
Move email to a named folder. Folder created automatically if it does not exist.
Destructive — stops the chain.
Supports nested folders with `/` separator.

```yaml
- type: move-to-folder
  folder: "Job Alerts/LinkedIn"
```

### `delete`
Permanently delete the email. Destructive — stops the chain.
Only available when `dry-run.enabled: false`. In dry-run mode, logged but not executed.

```yaml
- type: delete
```

### `send-email`
Send an outbound notification email via the configured SMTP server.
Non-destructive — does not stop the chain. Can be combined with `move-to-folder`.

```yaml
- type: send-email
  to: "${JOB_NOTIFY_ADDRESS}"
  subject: "[Job Alert] {source} — {email.subject}"
  body: "{llm.response}"
```

All `subject` and `body` fields support variable substitution (see Variable Reference).
Multiple `send-email` actions in one rule send multiple emails.

---

## Variable Reference

Variables available for substitution in `prompt`, `subject`, and `body` fields.

### Email Variables

| Variable | Description |
|---|---|
| `{email.from}` | Full sender address |
| `{email.subject}` | Original email subject line |
| `{email.body}` | Plain-text body (truncated to 8000 chars for LLM prompts) |
| `{email.body.html}` | HTML body, if available |
| `{email.date}` | Received date (ISO-8601) |
| `{email.to}` | Recipient address |
| `{email.message-id}` | Message-ID header value |

### Source Variables (extraction filters with `source-domains` or `source-subjects`)

| Variable | Description |
|---|---|
| `{source}` | Human-readable source name from whichever gate matched — `source-domains` map value (e.g. `LinkedIn`) or `source-subjects` map value (e.g. `Canadian Blood Services`). Falls back to sender address if no gate is configured. |
| `{source.domain}` | Raw matched domain (e.g. `linkedin.com`). Empty string if matched via `source-subjects`. |

### LLM Response Variables (actions only)

| Variable | Description |
|---|---|
| `{llm.response}` | Full trimmed LLM response text. Available in `on-response` actions. |

### Data Variables (extraction filters)

Any key defined under `data:` is available as `{key-name}` in the prompt.
Lists are formatted as comma-separated values.

```yaml
data:
  levels: [Director, VP, Manager]
  # → {levels} → "Director, VP, Manager"
```

### Scoring Variables (scoring filter prompts only)

| Variable | Description |
|---|---|
| `{preprocessor.findings}` | Structured findings from the pre-processor layer |
| `{preprocessor.score}` | Normalized pre-processor score (1–10) |
| `{spamassassin.score}` | Normalized SpamAssassin score (1–10) |
| `{spamassassin.raw-score}` | Raw SpamAssassin score |

---

## SMTP Configuration

Required for any filter using the `send-email` action.

```yaml
# application.yml
spring:
  mail:
    host: ${SMTP_HOST:smtp.gmail.com}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true

smtp:
  from-address: ${SMTP_FROM_ADDRESS:${SMTP_USERNAME}}
  from-name: "AI Email Filter"
```

Common environment variables for `docker-compose.yml`:

```
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=sender@gmail.com
SMTP_PASSWORD=app-specific-password
SMTP_FROM_ADDRESS=sender@gmail.com
JOB_NOTIFY_ADDRESS=paul@aksentiev.ca
SELF_EMAIL=paul@aksentiev.ca
```

---

## Complete `filters.yml` Example

```yaml
filters:

  # ── Logistics (runs first — no LLM, fast routing of tagged subjects) ────────

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

  # ── Scoring ──────────────────────────────────────────────────────────────────

  spam-filter:
    type: scoring
    enabled: true
    ollama-model: "qwen3.5:9b"
    whitelist:
      domains:
        - "aksentiev.ca"
        - "aksentyev.net"
    pre-processor:
      enabled: true
      brands-file: "brands.json"
      char-substitutions-file: "char_substitutions.json"
    spamassassin:
      enabled: true
      host: "spamassassin"
      port: 783
      skip-llm-above-score: 12.0
    scoring:
      weights:
        preprocessor: 0.20
        spamassassin: 0.35
        llm: 0.45
      thresholds:
        safe-max: 3
        review-max: 6
      actions:
        safe:   leave
        review: move-to-junk
        spam:   move-to-junk
    prompt: |
      You are a spam detection engine. Analyze this email.

      Pre-processor findings:
      {preprocessor.findings}

      SpamAssassin score: {spamassassin.score} / 10

      From: {email.from}
      Subject: {email.subject}
      Body:
      {email.body}

      Return ONLY valid JSON: {"score": <1-10>, "reason": "<brief explanation>"}

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
      monster.com: "Monster"
      workopolis.com: "Workopolis"
      jobbank.gc.ca: "Job Bank Canada"
    source-subjects:
      "New jobs posted by Canadian Blood Services": "Canadian Blood Services"
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
        - type: move-to-folder
          folder: "Jobs/Job Search Emails/Processed"
      always:
        - type: delete

  newsletter-filter:
    type: extraction
    enabled: true
    ollama-model: "qwen3.5:9b"
    source-domains:
      substack.com: "Substack"
      beehiiv.com: "Beehiiv"
      mailchimp.com: "Mailchimp"
    prompt: |
      Summarize this newsletter in 5 concise bullet points.
      Focus on key information, announcements, or insights.
      Plain text only. No preamble.

      If this is NOT a newsletter, respond with exactly: NONE
    actions:
      on-response:
        - type: send-email
          to: "${SELF_EMAIL}"
          subject: "[LLM Summary] {email.subject}"
          body: "{llm.response}"
      always:
        - type: move-to-folder
          folder: "Newsletters"


# ── Account assignments ────────────────────────────────────────────────────────

accounts:
  - name: "Personal"
    host: "imap.example.com"
    username: "${IMAP_USERNAME_1}"
    password: "${IMAP_PASSWORD_1}"
    filters:
      - logistics-filter        # fast routing first, no LLM
      - spam-filter
      - job-search-filter
      - newsletter-filter

  - name: "Business"
    host: "imap.business.com"
    username: "${IMAP_USERNAME_2}"
    password: "${IMAP_PASSWORD_2}"
    filters:
      - spam-filter             # spam only on business account
```

---

## Java Execution Engine — What Needs to Be Built

For reference: the Java components that serve all three filter types.
No new Java class should ever be needed to add a new filter.

### New components

| Component | Purpose |
|---|---|
| `ExtractionFilter` | Executes extraction-type filters. Handles `source-domains` and `source-subjects` gates, prompt variable injection, LLM call, `NONE` detection, `on-response`/`always` action dispatch |
| `LogisticsFilter` | Executes logistics-type filters. Evaluates condition rules top-to-bottom, stop-on-first-match |
| `ConditionEvaluator` | Evaluates single conditions and `all-of`/`any-of`/`not` compositions against an `EmailMessage` |
| `ActionExecutor` | Executes the action catalogue. Handles `move-to-folder`, `send-email`, `flag`, `delete`, `leave`, `move-to-junk` |
| `SmtpEmailSender` | Outbound SMTP via `JavaMailSender`. Used by `ActionExecutor` for `send-email` actions |
| `VariableResolver` | Substitutes `{variable}` tokens in prompt/subject/body strings |

### Changes to existing components

| Component | Change |
|---|---|
| `FilterResult` | Add `MOVE_TO_FOLDER` action variant carrying a `targetFolder` string |
| `EmailActionService` | Handle `MOVE_TO_FOLDER` (create folder if absent, move message) and `SEND_EMAIL` |
| `FilterChainDispatcher` | Recognise filter `type` field; dispatch to `ScoringFilter`, `ExtractionFilter`, or `LogisticsFilter` accordingly |
| `FiltersConfig` | Bind new filter-type fields: `type`, `source-domains`, `source-subjects`, `data`, `actions.on-response`, `actions.always`, `rules` |

### No new Java needed to add a filter

Once the above is built, the full workflow for a new filter is:

1. Add entry to `filters.yml`
2. Add filter name to the account's filter list
3. Reload config via the reload API
4. Done
