# CLAUDE.md — AI Email Filtering Platform

## What This Is

A home lab project building a production-grade AI email processing platform.
Primary language: Java 21 / Spring Boot 3.x / Spring AI.
Deployed as Docker containers on a home server (192.168.10.180).

The full design history, architecture decisions, and YAML examples are in
`ai-email-filtering-project-summary.md` — treat it as the source of truth
for design intent.

---

## Current State

- Python prototype: complete (functional spec and reference implementation only)
- Spring Boot implementation: in progress (this is the active codebase)

---

## Architecture — Three Tiers

### Tier 1 — Guard (real-time, per email) ← CURRENT BUILD

Spam gate. Runs first, always. High score stops the chain.
Three-layer scoring pipeline:

- Layer 1: Pre-processor (character normalization, brand impersonation, URL analysis)
- Layer 2: SpamAssassin (headers, SPF/DKIM/DMARC, Bayesian scoring)
- Layer 3: LLM via Ollama (semantic analysis, catches what SA misses)
- Weighted scoring: PP=20%, SA=35%, LLM=45% (configurable, graceful degradation)

### Tier 2 — Organizer (real-time, per email) ← CURRENT BUILD

YAML-defined filter chain. Runs after spam gate clears an email.
Filters are pure definitions — no code changes to add a new filter.
Each filter has conditions (AND/OR/NOT, two levels max) and actions.
Supports LLM-powered conditions via prompt.
Filters auto-sorted: non-stopping filters run before stopping filters.

### Tier 3 — Advisor (scheduled, batch) ← FUTURE, SEPARATE MICROSERVICE

Morning digest, folder summaries, cross-email intelligence.
Will have its own RAG layer, may use a more powerful LLM (Qwen3 14B).
Not designed yet — do not conflate with Tier 1/2.

---

## Infrastructure

### Home Server (192.168.10.180) — primary deployment

- Docker + Portainer
- RTX 2060 Super 8GB — runs Ollama with Mistral 7B

### LLM Server (192.168.10.158) — future Tier 3

- RTX 5060 Ti 16GB — runs Qwen3 14B, Mistral Nemo 12B
- Not used by current build

### Docker Containers (current build)

1. `email-processor` — this Spring Boot app
2. `spamassassin` — called via spamc protocol
3. `ollama` — already running, shared

---

## Package Structure

```
ca.aksentiev.emailfilter
│
├── email
│   ├── imap          # ImapIdleMonitor, connection management, graceful shutdown
│   └── parser        # EmailParser, ParsedEmail record
│
├── filter
│   ├── api           # Filter interface, FilterResult record, FilterAction enum
│   │                 # ConditionEvaluator, FilterEngine (loads + executes chain)
│   └── spam          # SpamFilter (special — three-layer pipeline, always gate)
│
├── preprocessor      # PreProcessorService, PreProcessorFindings record
│
├── spamassassin      # SpamAssassinClient, SpamAssassinResult record
│
├── scoring           # ScoringService, ScoreResult record, LayerScore record
│
├── llm               # LlmScoringService, LlmResponse record
│
├── action            # EmailActionService, AuditService (interface)
│                     # SubjectTagger
│
├── audit             # LoggingAuditService (Phase 1 stub, replace with SQLite later)
│
└── config            # All @ConfigurationProperties classes
```

---

## Coding Conventions

- **No Lombok** — plain Java only
- **Records** for immutable data carriers: DTOs, result objects, LLM responses,
  scoring results, pre-processor findings, parsed emails
- **Plain classes with constructor binding** for @ConfigurationProperties
- **Constructor injection only** — no @Autowired, no field injection;
  Spring auto-injects single constructors
- **@ConfigurationProperties** for all config binding — no @Value for structured data
- **Package by feature** as above
- **JUnit 5 + Mockito + AssertJ** for tests
- **No Lombok**
- **No silent exception swallowing** — log and rethrow or handle explicitly
- **No magic numbers** — all thresholds, weights, ports, paths come from config

---

## Configuration Files (all mounted as volumes, outside container)

| File                      | Purpose                                 | Reload API               |
|---------------------------|-----------------------------------------|--------------------------|
| `application.yml`         | Accounts, Ollama, SA connections, ports | Restart                  |
| `filters.yml`             | Filter chain definitions                | POST /api/reload/filters |
| `brands.json`             | Known brands + legitimate domains       | POST /api/reload/brands  |
| `char_substitutions.json` | Character normalization maps            | POST /api/reload/filters |
| `.env`                    | Secrets (passwords, API key)            | Restart                  |

### Reload API (management port 8081, internal only, API key protected)

```
POST /api/reload/filters    # re-reads filters.yml + char_substitutions.json
POST /api/reload/brands     # re-reads brands.json
POST /api/reload/all        # reloads everything
```

### Secret Handling

- IMAP passwords → environment variables: `${IMAP_PASSWORD_PERSONAL}`
- Reload API key → environment variable: `${RELOAD_API_KEY}`
- Nothing sensitive in any file that could end up in Git

---

## Filter Chain Execution Model

```
Email arrives via IMAP IDLE
        │
        ▼
Spam Filter (always first — gate)
        │
        ├── Score 7-10 → tag subject + action → STOP
        ├── Score 4-6  → tag subject + action → STOP
        └── Score 1-3  → continue to filter chain
                │
                ▼
        Non-stopping filters (auto-sorted first)
        tag, flag, summarize, forward
                │
                ▼
        Stopping filters (auto-sorted last)
        delete, move, archive
                │
                ▼
        Done
```

---

## Subject Tagging Format

Spam filter:

```
[SPAM: PP:3/SA:8/LLM:9=8] Original Subject
```

General filters (configurable tag template per filter in filters.yml):

```
[FLYER: MacBook Air @ Best Buy] Original Subject
[STEAM: Helldivers 2 Sale] Original Subject
```

X-headers added to every processed email for auditing:

```
X-EmailFilter-Score: 8
X-EmailFilter-Reason: Phishing attempt detected
X-EmailFilter-Action: moved-to-review
X-EmailFilter-Filter: spam-filter
```

---

## Condition Model (filters.yml)

Two levels max. No deeper nesting — ever.

```yaml
conditions:
  operator: "AND"
  groups:
    - operator: "OR"
      rules:
        - type: "sender-contains"
          value: "bestbuy.com"
        - type: "sender-contains"
          value: "staples.com"
    - operator: "AND"
      rules:
        - type: "llm-prompt"
          prompt: "Does this email mention MacBook Air on sale? YES or NO only."
          expect: "YES"
        - type: "subject-contains"
          value: "sale"
          not: true
```

### Condition Types

Simple (no LLM):

- `sender-contains`, `sender-matches`
- `subject-contains`, `subject-matches`
- `body-contains`
- `header-equals`

LLM-powered:

- `llm-prompt` — prompt with email content, expect specific response

---

## Python → Spring Boot Component Mapping

| Python          | Spring Boot                      | Package      |
|-----------------|----------------------------------|--------------|
| preprocessor.py | PreProcessorService              | preprocessor |
| sa_client.py    | SpamAssassinClient               | spamassassin |
| llm_client.py   | LlmScoringService                | llm          |
| scorer.py       | ScoringService                   | scoring      |
| actions.py      | EmailActionService               | action       |
| imap_client.py  | ImapIdleMonitor                  | email.imap   |
| config.py       | @ConfigurationProperties classes | config       |
| —               | FilterEngine                     | filter.api   |
| —               | ConditionEvaluator               | filter.api   |

---

## Phased Rollout

### Phase 1 (current)

- Score 1-3: leave in inbox
- Score 4-6: move to LLM-Spam-Review, tag subject
- Score 7-10: move to LLM-Spam-Review, tag subject
- Nothing deleted — build confidence in scoring first

### Phase 2 (when confident)

- Score 7-10: auto-delete
- Thresholds configurable in filters.yml, no code change needed

---

## Future Stages (do not implement now)

### RAG Layer (Tier 1/2)

- User feedback via folder moves (confirmed spam / false positive)
- Embeddings via nomic-embed-text (Ollama)
- Vector store: ChromaDB or SQLite-VSS
- Enriches LLM prompt with similar past emails

### Fine-Tuning (Tier 1/2)

- QLoRA fine-tune Mistral 7B once 500-1000 labeled examples accumulate
- Bake stable patterns into weights, reset RAG
- Repeat quarterly

### Tier 3 — Advisor Microservice

- Separate deployment, separate RAG
- Scheduled batch processing, digest emails, folder summaries
- May use LLM server (192.168.10.158) with Qwen3 14B
- Not designed yet
