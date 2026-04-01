# emailfilter

Spring Boot project for AI Email Filtering platform.

## Prerequisites

- **Java 21** (for local development)
- **Docker & Docker Compose** (for deployment)
- **Ollama** (external dependency — must be running separately)

### Ollama

Ollama provides the LLM layer (Layer 3) of the spam scoring pipeline. It is
**not** managed by Docker Compose — you must run it yourself on a host with a
GPU. Point `spring.ai.ollama.base-url` in your `application.yml` to its address.

If Ollama is unreachable, the system **degrades gracefully**: the SpamAssassin
and pre-processor layers continue scoring, and the LLM weight is automatically
redistributed across the remaining layers. No emails are lost or left unprocessed.

## Quick Start

```bash
# 1. Build the application
./gradlew bootJar

# 2. Copy and edit configuration
cp config/application.yml.example config/application.yml
cp .env.example .env
# Edit config/application.yml — set IMAP host, username, Ollama URL, etc.
# Edit .env — set real passwords and API key.

# 3. Start services
docker compose up -d
```

The system starts in **dry-run mode** by default (`dry-run.enabled: true`).
In this mode it runs the full scoring pipeline but only logs and tags email
subjects — it will not move or delete any emails. Review the dry-run reports
to tune scoring weights and thresholds, then set `dry-run.enabled: false`
when you are confident in the results.

## Configuration

| File | Purpose | Hot-reload |
|------|---------|------------|
| `config/application.yml` | All application settings | Restart |
| `config/brands.json` | Known brands + legitimate domains | `POST /api/reload/brands` |
| `config/char_substitutions.json` | Character normalization maps | `POST /api/reload/filters` |
| `.env` | Secrets (IMAP passwords, API key) | Restart |

See `config/application.yml.example` for all configurable properties with
comments explaining each section.