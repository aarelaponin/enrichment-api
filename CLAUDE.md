# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **enrichment-api** plugin — a Joget DX 8.1 API Builder plugin providing REST endpoints for the Manual Enrichment Workspace (F01.05). It reads/writes enrichment transaction records from `trx_enrichment` via Joget's `FormDataDao`. The plugin is packaged as an OSGi bundle (Apache Felix).

The full specification is at `docs/enrichment-api-specification.md`.

## Build & Deploy

```bash
mvn clean package    # produces target/enrichment-api-8.1.0-SNAPSHOT.jar
```

Deploy by uploading the JAR via Joget Plugin Manager (Settings → Manage Plugins → Upload Plugin).

No test framework is configured — this is a Joget plugin that runs inside Joget's OSGi container.

## Architecture

- **Plugin class:** `EnrichmentApiPlugin` extends `ApiPluginAbstract`. All endpoints are methods annotated with `@Operation` on this single class. The API Builder framework discovers them via annotation scanning.
- **Activator:** `Activator.java` is the OSGi `BundleActivator` that registers the plugin.
- **Service layer:** `EnrichmentService` in `service/` — handles record CRUD, version-based optimistic locking, status transitions, split/merge. Custom exceptions: `RecordNotFoundException`, `VersionConflictException`, `TerminalStatusException`, `DeleteNotAllowedException`, `InvalidTransitionException`.
- **Data access:** All DB access uses Joget's `FormDataDao` Spring bean (obtained via `AppUtil.getApplicationContext().getBean("formDataDao")`). **No raw SQL or direct JDBC** — always use the Joget API. `JdbcHelper` in `service/` is available for edge cases.
- **Field mapping:** Dynamic — `rowToMap()` iterates all `FormRow` properties. No hardcoded field map constant. JSON keys use form element IDs directly (snake_case), matching Joget native patterns.
- **Validation config:** `ValidationConfig` reads confidence overrides and validation rules from plugin properties (API Builder UI).
- **Java version:** Source compiled for Java 11, runs on Java 17 (Tomcat 9.0.90).

## Joget API Builder Routing Limitations (Critical)

These are hard-won lessons — read before adding or modifying endpoints:

1. **Path variable routing is broken.** `GET /records/{id}`, `PUT /records/{id}`, and any sub-path like `POST /records/save` or `POST /records/confirm` return Joget framework 400. The `{id}` pattern greedily matches all `/records/*` paths.

2. **Joget is method-agnostic.** `@Operation(type = MethodType.GET)` also responds to POST. The `type` field is documentation-only.

3. **New `@Operation` methods are NOT detected after JAR redeployment.** Only operations registered when the API Builder was first configured are routed. Adding a new `@Operation` method and redeploying the JAR does **not** make it available — you must delete and re-create the API Builder configuration.

4. **Workaround: piggyback on existing endpoints.** The save operation uses `GET /records?save=<json>` — an extra `@Param` on the existing `records()` method that dispatches to `handleInlineSave()`. This avoids both the path collision and the new-operation detection issue. Use this pattern for any new functionality that needs a working endpoint.

## Endpoints

| Registered Path | Actual Behavior | Notes |
|----------------|-----------------|-------|
| `/health` | Health check | Works (GET/POST both) |
| `/records` | List records OR inline save | `?save=<json>` triggers save via `handleInlineSave()` |
| `/records/{id}` | Get single record | **Broken** — returns Joget 400. Detail panel uses cached data instead. |
| `/records/{id}` (PUT) | Update record | **Broken** — same path variable issue. Use `?save=` instead. |
| `/records/{id}/status` | Status transition | **Broken** — collides with `{id}` pattern |
| `/records/status` | Batch status transition | **Broken** — collides with `{id}` pattern |
| `/records/{id}` (DELETE) | Delete record | **Broken** — same path variable issue |
| `/summary` | Statement summary counts | Works |
| `/reconciliation/{statementId}` | Reconciliation data | May have path variable issue |
| `/records/confirm` | Confirm for posting | **Broken** — collides with `{id}` pattern |
| `/records/{id}/split` | Split record | **Broken** — collides with `{id}` pattern |
| `/records/merge` | Merge records | **Broken** — collides with `{id}` pattern |

**Working pattern:** For any endpoint that needs to actually work, piggyback on `/records` or `/health` via extra `@Param` parameters. Example: `?save=<json>` on `/records`.

## Reference Codebases

When encountering a new architectural pattern, API usage question, or implementation problem, consult these reference sources for proven examples before inventing solutions:

1. **Joget Community Edition source:** `/Users/aarelaponin/IdeaProjects/rsr/joget/jw-community` — the platform source code; authoritative reference for how FormDataDao, AppService, and all core APIs work internally.
2. **Joget API Builder source:** `/Users/aarelaponin/IdeaProjects/rsr/joget/api-builder` — the API Builder plugin framework; reference for `@Operation`, `@Param`, `ApiPluginAbstract`, and endpoint registration.
3. **Existing plugins (gs-plugins):** `/Users/aarelaponin/IdeaProjects/rsr/gs-plugins` — production plugins from another project; proven patterns for plugin structure, OSGi bundles, FormDataDao usage, and Joget API integration.

## Development Phases

The plugin is being built incrementally. Phases 0–3 are complete: health, records listing, single record (broken path), inline save (via `?save=` workaround). See `../DEV-PLAN.md` for the full plan.

## Critical FormDataDao Conventions

These are the most common sources of bugs in this codebase:

1. **No `c_` prefix in code.** Hibernate property names use form element IDs (e.g., `transaction_date`). The `c_` prefix is only for DB column names, added automatically by FormDataDao's dynamic Hibernate mapping. Use `transaction_date` everywhere — in HQL conditions, sort fields, and `FormRow.getProperty()`.

2. **HQL condition syntax:** Use `e.customProperties.<field_id>` (e.g., `e.customProperties.processing_status = ?`).

3. **Empty conditions must be `""`, not `null`.** `FormDataDao.count()` (`internalCount`) concatenates the condition string without a null check, producing invalid HQL `"... e null"`. `find()` has a null check but `count()` does not.

4. **Sort fields:** Pass just the form element ID (e.g., `transaction_date`). FormDataDao auto-prepends `customProperties.` for custom fields. Standard fields (`id`, `dateCreated`, `dateModified`) are used as-is.

5. **Params array:** Pass `new Object[0]` (not `null`) when there are no filter parameters.

## Known Pitfalls

- `getTag()` must return a plain string with no `{variable}` placeholders — otherwise API Builder silently skips the plugin.
- Do not override `getResourceBundlePath()` unless the resource bundle file exists.
- New endpoints require enabling in API Builder's ENABLED_PATHS after JAR deployment — but even then, new `@Operation` methods added after initial configuration may not be detected (see routing limitations above).