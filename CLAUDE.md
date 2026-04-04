# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Goal

DuckDuckBurp is a Burp Suite extension that stores Burp proxy traffic in DuckDB to enable fast SQL querying and AI-assisted analysis.

## Architecture

Built as a fat JAR loaded into Burp Suite at runtime. All source is at the repo root.

- **Entry point**: `src/main/java/Extension.java` — implements `BurpExtension`, receives a `MontoyaApi` instance via `initialize()`
- **Build system**: Gradle with Kotlin DSL (`build.gradle.kts`), Java 21
- **Montoya API**: `net.portswigger.burp.extensions:montoya-api:2026.2` — compile-only dependency (Burp provides it at runtime)
- **JAR packaging**: Fat JAR via `tasks.named<Jar>("jar")` — bundles runtime classpath (DuckDB JDBC, flexmark-java)

The Montoya API is the sole bridge to Burp. All Burp interaction goes through the `MontoyaApi` object passed to `initialize()`.

## Build Commands

```bash
./gradlew jar      # Build the extension JAR → build/libs/DuckDuckBurp.jar
./gradlew build    # Build + run tests
./gradlew test     # Run tests only
./gradlew clean    # Clean build artifacts
```

## Definition of Done

Before claiming any task complete:
1. Write tests for new logic (unit tests live in `src/test/java/`)
2. Run `./gradlew build` — it must pass with zero test failures
3. Never declare success based on compilation alone

## Loading into Burp Suite

1. Build: `./gradlew jar`
2. In Burp: **Extensions > Installed > Add** → select the JAR from `build/libs/`
3. Quick reload during dev: `Ctrl`/`⌘` + click the **Loaded** checkbox

## Key Montoya API Patterns

```java
// Proxy history access
montoyaApi.proxy().history();

// HTTP requests (always use Burp networking, not raw Java sockets)
montoyaApi.http().sendRequest(request);

// Logging
montoyaApi.logging().logToOutput("message");

// AI features (check before use)
if (montoyaApi.ai().isEnabled()) { ... }
```

- Use background threads for any slow operations (DB writes, HTTP requests, AI calls) — never block the Swing EDT or proxy handlers
- Clean up threads/connections in `montoyaApi.extension().registerUnloadingHandler()`
- GUI elements must be parented to the Burp main frame via `SwingUtilities.getWindowAncestor(panel)`

## Documentation

- `docs/montoya-api-examples.md` — code patterns for UI, context menus, settings panels, AI
- `docs/development-best-practices.md` — AI feature guidelines
- `docs/bapp-store-requirements.md` — quality/security requirements if submitting to BApp Store
