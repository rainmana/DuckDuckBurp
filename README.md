# DuckDuckBurp

> SQL querying and AI-assisted analysis over your Burp Suite proxy traffic, powered by [DuckDB](https://duckdb.org/).

Burp's proxy history is great for individual request inspection, but terrible for answering questions like *"which endpoints returned 200 to unauthenticated requests?"* or *"where are numeric IDs in paths that could be IDORs?"*. DuckDuckBurp solves this by capturing every proxied response into a local DuckDB database and giving you a full SQL interface — plus an AI analyst — directly inside Burp.

```sql
-- Find paths that returned 200 without auth tokens
SELECT DISTINCT host, path, status_code
FROM traffic
WHERE status_code = 200
  AND lower(req_headers::VARCHAR) NOT LIKE '%authorization%'
  AND regexp_extract(lower(path), 'admin|api|internal|private') <> ''
ORDER BY host, path
```

---

## Features

### SQL Query Tab
- Write any [DuckDB SQL](https://duckdb.org/docs/sql/introduction) against your live traffic — full `SELECT`, `WHERE`, `GROUP BY`, window functions, regex, JSON access, everything
- **50+ canned queries** covering Recon, Authentication, Input Surface, Errors & Leakage, OWASP Top 10, CWE Web, and Security — click any to load and run instantly
- Click a result row to see the full raw request and response in the detail pane
- Save your own queries to a shared library that persists across all Burp projects
- Export results to **CSV**, **JSON**, or **Parquet** via DuckDB's `COPY` command
- Keyboard shortcut: **Ctrl+Enter** to run

### Dashboard Tab
- Live traffic stats: total requests, unique hosts, method breakdown, top status codes, auth failure count, server error count
- Auto-refreshes as traffic flows in

### AI Analyst Tab
- Ask plain-English questions about your traffic; the AI generates a summary and suggests targeted SQL queries
- Works with **Burp AI** (uses your Burp AI credits) or **any OpenAI-compatible endpoint** — OpenAI, Ollama, LM Studio, Azure OpenAI, etc.
- Suggested SQL queries appear below the response with one-click **▶ Run** and **💾 Save** buttons
- AI response renders as formatted markdown (toggle to raw text at any time)
- Optionally includes a compact traffic summary in the prompt for context-aware answers

### Data & Privacy
- **Everything is local** — all traffic data is written to DuckDB files on your own machine, never sent anywhere
- **Per-project isolation** — each Burp project file gets its own traffic database; switching projects shows only that engagement's traffic
- **Shared query library** — saved queries persist across all projects in a separate shared database

---

## Requirements

- Burp Suite Professional or Community Edition (2023.1 or later with Montoya API support)
- Java 21 — bundled with modern Burp Suite, no separate install needed

---

## Installation

### From the BApp Store *(recommended)*

1. In Burp: **Extensions → BApp Store**
2. Search for **DuckDuckBurp**
3. Click **Install** — all dependencies are bundled, no additional setup required

### Manual install

1. Download `DuckDuckBurp.jar` from the [Releases](https://github.com/rainmana/DuckDuckBurp/releases) page
2. In Burp: **Extensions → Installed → Add**
3. Select the downloaded JAR and click **Next**
4. The **DuckDuckBurp** tab appears in the main Burp window

### Build from source

```bash
git clone https://github.com/rainmana/DuckDuckBurp.git
cd DuckDuckBurp
./gradlew jar
# Output: build/libs/DuckDuckBurp.jar
```

Requires Java 21+. All other dependencies are fetched automatically by Gradle.

---

## Quick start

1. Load the extension and browse your target normally through Burp's proxy
2. Open the **DuckDuckBurp** tab — the **Dashboard** shows live stats as traffic flows in
3. Click the **Query** tab and pick a canned query from the sidebar, or write your own
4. Click a result row to view the full request/response in the detail pane below
5. Open the **AI Analyst** tab, configure your AI backend in **Settings**, and ask a question

---

## Data storage

| Data | Location |
|---|---|
| Traffic (per project) | `~/.burp/duckduckburp/<projectId>.db` |
| Saved queries (all projects) | `~/.burp/duckduckburp/shared.db` |

A unique 12-character project ID is generated on first load per Burp project file and stored in Burp's project-scoped preferences. The full path to the active traffic database is logged to the extension output panel on startup.

You can query the database directly with the [DuckDB CLI](https://duckdb.org/docs/installation/) outside of Burp if needed.

---

## Using the Query tab

### Writing queries

The editor accepts any valid DuckDB SQL. Press **Ctrl+Enter** or click **Run Query** to execute. Results appear in the table below; click any row to load the full request and response in the detail pane.

If your query doesn't include the `id` column, the detail pane shows a hint asking you to add it.

### Canned queries

The left sidebar groups 50+ built-in queries by category. Click any query name to instantly load and run it. These are read-only — they live in code and are always available regardless of which project you have open.

### Saving queries

1. Write your query in the editor
2. Click **Save Query…**
3. Enter a name and choose or type a category
4. The query is saved to `shared.db` and appears in the **★ Saved Queries** section of the sidebar in every project

Right-click a saved query in the sidebar to delete it. Use **Import…** and **Export…** to share query libraries as JSON files.

### Exporting results

Click **Export…**, choose CSV / JSON / Parquet, and pick a save location. DuckDB writes the file directly — no row-count limits.

### Traffic table schema

```sql
CREATE TABLE traffic (
    id           BIGINT PRIMARY KEY,   -- Burp's internal message ID
    timestamp    TIMESTAMPTZ,          -- time the response was captured
    host         VARCHAR,              -- e.g. "example.com"
    port         INTEGER,
    protocol     VARCHAR,              -- "http" or "https"
    method       VARCHAR,              -- "GET", "POST", etc.
    path         VARCHAR,              -- full path including query string
    status_code  INTEGER,
    req_headers  JSON,                 -- request headers as JSON object
    req_body     VARCHAR,              -- request body (text)
    resp_headers JSON,                 -- response headers as JSON object
    resp_body    VARCHAR,              -- response body (text)
    resp_length  INTEGER               -- response body byte length
)
```

### Example queries

```sql
-- IDOR candidates: paths with numeric IDs, multiple variations seen
SELECT host, method,
       regexp_replace(path, '[0-9]+', '{id}') AS path_pattern,
       COUNT(DISTINCT path) AS id_variations
FROM traffic
WHERE regexp_extract(path, '/[0-9]+') <> ''
GROUP BY host, method, path_pattern
ORDER BY id_variations DESC
LIMIT 20;

-- Unauthenticated access to privileged-looking endpoints
SELECT id, host, method, path, status_code
FROM traffic
WHERE status_code = 200
  AND lower(req_headers::VARCHAR) NOT LIKE '%authorization%'
  AND regexp_extract(lower(path),
      'admin|manager|internal|private|restricted|staff') <> ''
ORDER BY host, path;

-- POST/PUT/DELETE requests missing CSRF headers
SELECT id, host, method, path, status_code
FROM traffic
WHERE method IN ('POST', 'PUT', 'DELETE', 'PATCH')
  AND lower(req_headers::VARCHAR) NOT LIKE '%csrf%'
  AND lower(req_headers::VARCHAR) NOT LIKE '%xsrf%'
ORDER BY id DESC
LIMIT 50;
```

---

## Using the AI Analyst tab

### Setup

Go to the **Settings** tab and choose an AI backend:

**Burp AI** — uses your Burp AI credit balance. Requires AI features to be enabled in Burp settings (`Settings → AI`).

**Custom endpoint** — any OpenAI-compatible API:

| Field | Example |
|---|---|
| URL | `https://api.openai.com/v1` or `https://api.openai.com/v1/chat/completions` |
| API key | `sk-…` |
| Model | `gpt-4o-mini` |

Compatible with OpenAI, LiteLLM (`http://127.0.0.1:4000/v1`), Ollama (`http://localhost:11434/v1`), LM Studio, Azure OpenAI, and similar services. DuckDuckBurp accepts either the API base URL or a full OpenAI-compatible text endpoint, and it can automatically use `chat/completions`, `responses`, or legacy `completions` for text-capable LiteLLM models.

For LiteLLM specifically, use a text-generation model alias in the **Model** field. Embedding, image, audio, moderation, realtime, and other non-text models are not valid for the AI Analyst tab.

### Asking questions

Type your question in the text area and press **Ctrl+Enter** or **Ask AI**. Leave **Include traffic summary** checked to give the AI a compact context snapshot (request counts, top hosts, status distribution, recent paths).

Example prompts:
- *"Summarize the attack surface and highlight anything worth investigating"*
- *"Are there any endpoints that look vulnerable to IDOR?"*
- *"What authentication mechanisms are in use and where might they be bypassable?"*
- *"Generate a query to find all JSON endpoints that accept user-supplied IDs"*

### Suggested queries

After each response, any SQL code blocks the AI produced appear in the **Suggested Queries** panel:
- **▶ Run** — switches to the Query tab and executes immediately
- **💾 Save** — opens the save dialog with an auto-suggested category based on the query content

---

## Canned query reference

| Category | What it finds |
|---|---|
| **Recon** | All hosts and request counts, full URL inventory, HTTP methods in use, status code breakdown, traffic timeline by minute |
| **Authentication** | All 401/403 responses, paths with mixed auth results (bypass candidates), login/auth/SSO endpoints, requests with Bearer tokens or API keys |
| **Input Surface** | Non-GET endpoints, endpoints with query parameters, requests with bodies, JSON content-type requests |
| **Errors & Leakage** | All 5xx server errors, all 4xx client errors, largest responses, 404 patterns, large error response bodies |
| **OWASP Top 10** | A01 mixed-auth paths, A01 privileged paths returning 200, A02 sensitive data over HTTP, A03 injection patterns, A05 debug/config endpoints, A07 auth failure hotspots, A10 SSRF indicators |
| **CWE Web** | CWE-79 XSS script patterns, CWE-89 SQL injection payloads, CWE-22 path traversal sequences, CWE-352 state-changing requests without CSRF tokens, CWE-601 open redirect parameters, CWE-918 SSRF URL parameters |
| **Security** | Interesting paths (admin/debug/backup/config/…), numeric ID patterns for IDOR analysis, API versioned endpoints, 3xx redirect chains, potential file upload endpoints |

---

## Changelog

### v1.0.1

- Improved LiteLLM Proxy compatibility for local, Docker, and CLI-backed setups
- Accepts either a base URL like `http://127.0.0.1:4000/v1` or a full endpoint URL
- Automatically supports OpenAI-compatible text endpoints across `chat/completions`, `responses`, and legacy `completions`
- Added regression tests covering endpoint selection, fallback behavior, and response parsing

### v1.0.0

- Initial public release of DuckDuckBurp
- DuckDB-backed traffic capture, query UI, saved queries, dashboard, and AI Analyst tab

For the full release history, see [GitHub Releases](https://github.com/rainmana/DuckDuckBurp/releases).

---

## License

[Apache License 2.0](LICENSE)
