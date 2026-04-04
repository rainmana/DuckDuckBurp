# DuckDuckBurp

A Burp Suite extension that stores proxy traffic in [DuckDB](https://duckdb.org/) and lets you interrogate it with SQL and AI.

Instead of clicking through Burp's proxy history, write a query:

```sql
SELECT host, path, status_code, resp_length
FROM traffic
WHERE status_code = 200
  AND regexp_extract(lower(path), 'admin|debug|backup|config') <> ''
ORDER BY resp_length DESC
```

Then ask your AI model what it means.

---

## Features

- **SQL over HTTP traffic** — every proxied response is stored in a local DuckDB database with full request/response detail
- **50+ canned queries** — organised by category: Recon, Authentication, Input Surface, Errors & Leakage, OWASP Top 10, CWE Web, Security
- **Save your own queries** — stored in a shared library that persists across all Burp projects
- **Export results** — CSV, JSON, or Parquet via DuckDB's `COPY` command
- **AI Analyst tab** — ask questions in plain English; the AI suggests DuckDB queries you can run or save with one click
- **AI backends** — Burp's built-in AI (requires Burp AI credits) or any OpenAI-compatible endpoint (OpenAI, Ollama, LM Studio, …)
- **Per-project isolation** — each Burp project file gets its own traffic database; saved queries are shared across all projects
- **Rendered markdown** — AI responses render with syntax-highlighted code blocks and toggle back to raw text

---

## Requirements

- Burp Suite Professional (or Community) with the Montoya API
- Java 21 (bundled with modern Burp)

---

## Installation

### From the BApp Store

Search for **DuckDuckBurp** in **Extensions → BApp Store** and click Install.

### Manual install

1. Download `DuckDuckBurp.jar` from the [Releases](https://github.com/rainmana/DuckDuckBurp/releases) page.
2. In Burp: **Extensions → Installed → Add** → select the JAR.
3. The **DuckDuckBurp** tab appears in the Burp suite.

### Build from source

```bash
git clone https://github.com/rainmana/DuckDuckBurp.git
cd DuckDuckBurp/ExtensionTemplateProject
./gradlew jar
# JAR is at build/libs/DuckDuckBurp.jar
```

---

## Data storage

| What | Where |
|---|---|
| Traffic (per project) | `~/.burp/duckduckburp/<projectId>.db` |
| Saved queries (shared) | `~/.burp/duckduckburp/shared.db` |

The project ID is a 12-character UUID generated on first load and stored in Burp's project-scoped preferences — so each Burp project file gets completely isolated traffic data. The path to the active database is logged to the Burp extension output on load.

---

## Using the Query tab

Write any DuckDB SQL in the editor and press **Ctrl+Enter** (or **Run Query**). Click any result row to see the full request and response in the detail pane below.

The sidebar lists all canned queries grouped by category. Click a query to load and run it instantly.

**Save a query** — click **Save Query…**, enter a name and category. It appears in the sidebar under **★ Saved Queries** and is available in every future project.

**Export results** — click **Export…** to save the current query's output as CSV, JSON, or Parquet.

### Traffic table schema

```sql
traffic (
    id           BIGINT PRIMARY KEY,
    timestamp    TIMESTAMPTZ,
    host         VARCHAR,
    port         INTEGER,
    protocol     VARCHAR,        -- 'http' or 'https'
    method       VARCHAR,
    path         VARCHAR,        -- includes query string
    status_code  INTEGER,
    req_headers  JSON,
    req_body     VARCHAR,
    resp_headers JSON,
    resp_body    VARCHAR,
    resp_length  INTEGER
)
```

---

## Using the AI Analyst tab

1. Configure your AI backend in the **Settings** tab (Burp AI or a custom OpenAI-compatible endpoint).
2. Type a question — or use the default prompt to get an attack-surface summary.
3. Click **Ask AI** or press **Ctrl+Enter**.

Any SQL queries the AI suggests appear in the **Suggested Queries** panel below the response:
- **▶ Run** — loads the query into the Query tab and executes it immediately
- **💾 Save** — saves it to your shared query library with an auto-suggested category

Toggle **Rendered** to switch between rendered markdown and raw text.

### Custom AI endpoint

In **Settings**, select **Custom endpoint** and fill in:

| Field | Example |
|---|---|
| URL | `https://api.openai.com/v1/chat/completions` |
| API key | `sk-...` |
| Model | `gpt-4o-mini` |

Any OpenAI-compatible server works (Ollama, LM Studio, Azure OpenAI, etc.).

---

## Canned query categories

| Category | Covers |
|---|---|
| **Recon** | Host inventory, URL enumeration, method/status breakdown, traffic timeline |
| **Authentication** | 401/403 responses, mixed-auth paths, login endpoints, bearer tokens |
| **Input Surface** | Non-GET endpoints, query parameters, request bodies, JSON requests |
| **Errors & Leakage** | 5xx/4xx errors, largest responses, 404 patterns |
| **OWASP Top 10** | A01 broken access control, A02 sensitive data, A03 injection, A05 debug endpoints, A07 brute force, A10 SSRF |
| **CWE Web** | CWE-79 XSS, CWE-89 SQLi, CWE-22 path traversal, CWE-352 CSRF, CWE-601 open redirect, CWE-918 SSRF |
| **Security** | Interesting paths, IDOR numeric IDs, API endpoints, redirects, file uploads |

---

## License

[MIT](LICENSE)
