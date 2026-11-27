# AppFunction Guard

**English** · [简体中文](README.zh-CN.md) · [Error codes](docs/ERROR_CODES.md) · [Changelog](CHANGELOG.md) · [Publishing](docs/PUBLISHING.md)

> English is the primary text. A full [简体中文](README.zh-CN.md) translation is maintained in parallel; both are updated in the same commit.

**Android 16 lets AI agents call your app's functions. Guard is the lock, the doorbell, and the visitor log it forgot to include.**

[![build](https://github.com/wang-sunyang/appfunction-guard/actions/workflows/build.yml/badge.svg)](https://github.com/wang-sunyang/appfunction-guard/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/badge/Maven_Central-0.1.0--alpha5_(pending)-blue.svg)](#install)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE.AGPLv3)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-green.svg)](LICENSE.Apache-2.0)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#status)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2-7F52FF.svg)](https://kotlinlang.org)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-green.svg)](#requirements)

---

## The problem

Android 16 ships **AppFunctions** — a platform API that lets an AI agent call functions inside your app directly, without going through your UI. The platform gives you the plumbing:

- a way to register functions
- a way to describe them to the model
- a way to execute them

What it does **not** give you:

| Missing | Consequence |
|---|---|
| Per-function authorization | An agent that can call `getNote` can also call `deleteAllNotes` |
| Argument validation | The model hallucinates `limit: -1` and your code trusts it |
| Rate limiting | A retry loop hits your DB 4,000 times in a minute |
| Audit trail | You cannot answer "what did the assistant do to my data last night?" |
| Consent UX | The user has no idea an agent is about to touch their photos |
| Structured errors | The agent gets `IllegalArgumentException` and retries forever |

These are not hypothetical. Recent Android security research on AppFunctions reports three concrete risk classes: **privacy leakage** through tool outputs, **isolation bypass** through over-broad function exposure, and **LLM integrity attacks** via malicious tool descriptions. Every one of them is a missing-policy problem, not a missing-API problem.

Guard is that policy layer.

---

## What it does

A single interception point between the platform and your business logic:

```
agent
  │
  ▼
AppFunctionService.onExecuteFunction()
  │
  ├─ 1. caller allow-list      who is calling?
  ├─ 2. policy                 is this function allowed at all?
  ├─ 3. rate limit             token bucket, per function × caller
  ├─ 4. argument validation    generated at compile time by KSP
  ├─ 5. user confirmation      only when the policy asks for it
  ├─ 6. execute                your code, unchanged
  ├─ 7. redaction              strip PII before it reaches the model
  └─ 8. audit                  one record per call, never the raw args
```

Everything runs before your business code. Nothing is a decorator you can forget to apply.

---

## Install

> **Maven coordinates (2026-11):** the groupId is now `io.github.wang-sunyang` (GitHub account rename). Only the groupId changes — artifactIds stay the same.
>
> `0.1.0-alpha5` is **not on Maven Central yet**. Every version published before it (up to `0.1.0-alpha4`) lives under the frozen legacy groupId, which is kept solely for the historical record — it will not receive updates. The full coordinate history is in [CHANGELOG.md](CHANGELOG.md) and the switch checklist in [docs/PUBLISHING.md](docs/PUBLISHING.md).

```kotlin
dependencies {
    implementation("io.github.wang-sunyang:guard-core:0.1.0-alpha5")
    ksp("io.github.wang-sunyang:guard-ksp:0.1.0-alpha5")

    // optional: ready-made confirmation dialog
    implementation("io.github.wang-sunyang:guard-ui:0.1.0-alpha5")
    // optional: audit records without pulling in Android
    implementation("io.github.wang-sunyang:guard-audit:0.1.0-alpha5")
}
```

Published on Maven Central — `mavenCentral()` in `repositories` is all you need, no JitPack and no `includeBuild`.

---

## Quick start

```kotlin
// 1. Application.onCreate
GuardConfig.init(this)
GuardConfig.confirmationHandler = MyConfirmDialog()   // your UI, 3 lines

// 2. In your AppFunctionService
override fun onExecuteFunction(functionId: String, args: Bundle): Bundle =
    BundleCodec.toBundle(
        AfGuard.intercept(functionId, args) { call ->
            when (call.functionId) {
                "listNotes"  -> repo.recent(call.intOr("limit", 20))
                "deleteNote" -> repo.delete(call.requireString("id"))
                else         -> null
            }
        }
    )

// 3. Declare the policy at the function
@Guard(policy = Policy.CONFIRM_EACH, risk = Risk.HIGH)
@RateLimit(calls = 5, perSeconds = 60)
@AppFunction
fun deleteNote(@TextLimit(max = 64) id: String): Boolean = repo.delete(id)
```

That's it. The annotations are read by KSP at build time; there is no reflection on the hot path.

---

## See it work

`sample/` is a runnable app. Every button in it is one agent call going through `AfGuard.intercept()`:

```bash
gradle :sample:installDebug   # no wrapper in the repo; use local Gradle 9.3.1, or run `gradle wrapper` first
```

| Button | What you should see |
|---|---|
| `listNotes · ALLOW` | returns immediately, no dialog, one `ALLOWED` audit row |
| `searchNotes("") · validate` | `INVALID_ARGUMENT` before your code runs, `field = keyword` |
| `deleteNote · confirm` | dialog with caller + risk; approval remembered for 7 days |
| `exportNotes · redact` | phone numbers come back as `138****8000` |
| `wipeAllNotes · blocked` | no dialog at all, `FUNCTION_BLOCKED`, `retryable = false` |
| `9x deleteNote` | first calls pass, then `RATE_LIMITED` with `retryAfterMs` |
| `audit` | one row per call, argument *shape* only, never values |

Setup notes, the real-device `AppFunctionService` wiring, and the two ways people get the confirmation dialog wrong: [sample/README.md](sample/README.md)

---

## Modules

| Module | Type | What's in it |
|---|---|---|
| `guard-audit` | pure JVM | `AuditRecord`, `DecisionKind`, `AuditSink` + CSV / JSONL export. No Android dependency, so it runs in CI and in plain JVM tools |
| `guard-core` | Android AAR | interception chain, policy engine, rate limiter, redactor, audit wiring |
| `guard-ksp` | KSP processor | generates argument validators and the function metadata table at build time |
| `guard-ui` | Android AAR | `DialogConfirmationHandler` — lifecycle aware, timeout safe, refuses instead of deadlocking on the main thread |
| `guard-cli` | JVM app | audit export / summary from a JSONL dump, for people who want the log out of the app |
| `guard-mcp` | JVM app | MCP server over stdio — exposes the policy engine to any MCP client as the `guard_check_function` tool |

`guard-audit` / `guard-core` / `guard-ksp` / `guard-ui` are published coordinates; `guard-cli` and `guard-mcp` are runnable apps, not library dependencies.

---

## Read the audit log offline

The library only writes *shapes* — decision, function, caller, argument fingerprint. Export it and you get a file you can hand to a reviewer:

```bash
# one line per call, from RingBufferAuditSink.toJsonLines()
gradle :guard-cli:installDist
./guard-cli/build/install/guard-cli/bin/guard-cli summary --in audit.jsonl

# to CSV, for a spreadsheet
./guard-cli/build/install/guard-cli/bin/guard-cli export --in audit.jsonl --out audit.csv --format csv

# or pipe it
cat audit.jsonl | ./guard-cli/build/install/guard-cli/bin/guard-cli summary
```

Unparseable lines are reported, not silently dropped: a truncated audit log is worse than a loud one.

---

## MCP server (`guard-mcp`)

If your agent runtime speaks [MCP](https://modelcontextprotocol.io) instead of AppFunctions, `guard-mcp` exposes the *same* policy engine over stdio. No business logic is duplicated: the module compiles Guard's own `PolicyEngine` / `RateLimiter` sources, so a policy decision made through MCP and a decision made inside an Android app come from one implementation.

```bash
gradle :guard-mcp:installDist
./guard-mcp/build/install/guard-mcp/bin/guard-mcp      # speaks JSON-RPC 2.0 on stdin/stdout
```

Tool: `guard_check_function` — a *policy oracle*. It answers "would this call be allowed?", it does not execute the function.

| Input | Type | Meaning |
|---|---|---|
| `function_id` | string | the function being guarded, e.g. `deleteNote` |
| `caller_id` | string | `<packageName>@<uid>`, e.g. `com.example.agent@10123` |
| `policy_context` | object | optional `policy`, `risk`, `rate_limit`, `consent` — see the YAML below |

| Output | Type | Meaning |
|---|---|---|
| `allowed` | boolean | whether the call may proceed |
| `code` | string | `ALLOW`, `CONFIRMATION_REQUIRED`, or a `GuardErrorCode` (`FUNCTION_BLOCKED`, `RATE_LIMITED`, …) |
| `retry_after_ms` | integer \| null | set only when `code = RATE_LIMITED` |

Machine-readable description (drop this into an MCP client config or a tool registry):

```yaml
name: guard_check_function
version: 0.1.0
description: >
  Policy oracle for AppFunctions-style tool calls. Returns whether a function
  call would be allowed, denied or require user confirmation, without executing it.
transport:
  type: stdio
  command: ./guard-mcp/build/install/guard-mcp/bin/guard-mcp
input_schema:
  type: object
  required: [function_id, caller_id]
  properties:
    function_id:
      type: string
      description: Function identifier as declared by the app (e.g. deleteNote)
    caller_id:
      type: string
      description: Caller identity as <packageName>@<uid>
      pattern: "^[A-Za-z0-9_.]+@[0-9]+$"
    policy_context:
      type: object
      description: Optional policy override; defaults come from GuardConfig
      properties:
        policy:
          type: string
          enum: [ALWAYS_DENY, CONFIRM_EACH, CONFIRM_ONCE, ALLOW]
          default: CONFIRM_ONCE
        risk:
          type: string
          enum: [LOW, MEDIUM, HIGH, CRITICAL]
          default: MEDIUM
        rate_limit:
          type: object
          properties:
            calls:        { type: integer, default: 10 }
            per_seconds:  { type: integer, default: 60 }
            burst:        { type: integer, default: 3 }
            scope:        { type: string, enum: [PER_CALLER, GLOBAL], default: PER_CALLER }
        consent:
          type: object
          properties:
            granted:        { type: boolean, default: false }
            expires_at_ms:  { type: integer }
output_schema:
  type: object
  required: [allowed, code]
  properties:
    allowed:        { type: boolean }
    code:
      type: string
      enum: [ALLOW, CONFIRMATION_REQUIRED, INVALID_ARGUMENT, MISSING_ARGUMENT, RATE_LIMITED, USER_DENIED,
             NOT_AUTHORIZED, FUNCTION_BLOCKED, FUNCTION_NOT_FOUND, CONFIRMATION_TIMEOUT, UPSTREAM_ERROR]
      description: Two values are decisions (ALLOW, CONFIRMATION_REQUIRED); the other nine are GuardErrorCode values, each carrying a retryable flag.
    retry_after_ms: { type: [integer, "null"] }
    retryable:      { type: boolean }
    hint:           { type: string }
```

The eleven values `code` can take, and what a client should do with each:

| `code` | `retryable` | Meaning |
|---|---|---|
| `ALLOW` | — | Policy allows the call; the caller may execute it. |
| `CONFIRMATION_REQUIRED` | — | Policy needs a human yes. Ask the user; do not retry blindly. |
| `INVALID_ARGUMENT` | `true` | An argument violates the declared constraints. Fix it, then retry. |
| `MISSING_ARGUMENT` | `true` | A required argument is absent. Supply it, then retry. |
| `RATE_LIMITED` | `true` | Token bucket is empty. Wait `retry_after_ms`, then retry. |
| `USER_DENIED` | `false` | The user said no. Do not retry; ask again first. |
| `NOT_AUTHORIZED` | `false` | This caller may not invoke the function. Do not retry. |
| `FUNCTION_BLOCKED` | `false` | The function is permanently closed to agents. Do not retry. |
| `FUNCTION_NOT_FOUND` | `false` | No policy is registered for this `function_id`. Do not retry. |
| `CONFIRMATION_TIMEOUT` | `true` | The user did not answer the confirmation in time. One retry later is fine. |
| `UPSTREAM_ERROR` | `false` | The app's own logic failed. Do not retry blindly; report it. |

`guard_check_function` never returns `allowed: true` for `ALWAYS_DENY`, and `CONFIRM_ONCE` never auto-passes `Risk.CRITICAL` — the same two rules the Android library enforces. A `CONFIRM_*` decision comes back as `allowed: false` with `code: CONFIRMATION_REQUIRED`, because an MCP server has no UI and must not invent consent on the user's behalf.

---

## Error protocol

The part most worth stealing even if you never use this library.

```json
{
  "afg_error": {
    "code": "RATE_LIMITED",
    "function": "deleteNote",
    "message": "Rate limit exceeded for 'deleteNote'",
    "retryAfterMs": 12000,
    "retryable": true,
    "hint": "Too many calls. Wait for retryAfterMs, then retry."
  }
}
```

`retryable` is the important field. An agent's default behaviour is *try again*; `retryable: false` is the only signal that reliably stops it.

| code | retryable | meaning |
|---|---|---|
| `INVALID_ARGUMENT` | yes | fix the argument, retry with `expected` |
| `MISSING_ARGUMENT` | yes | supply `field` |
| `RATE_LIMITED` | yes | wait `retryAfterMs` |
| `USER_DENIED` | **no** | the human said no. Stop. |
| `NOT_AUTHORIZED` | **no** | caller not allowed |
| `FUNCTION_BLOCKED` | **no** | permanently disabled |
| `CONFIRMATION_TIMEOUT` | yes | the human never answered; one retry later is fine |
| `UPSTREAM_ERROR` | **no** | your code broke, don't hammer it |

These are the nine `GuardErrorCode` members; the MCP tool's `output_schema.code` adds the two decision values on top of them. Full reference: [docs/ERROR_CODES.md](docs/ERROR_CODES.md)

---

## Annotations

| Annotation | Target | Purpose |
|---|---|---|
| `@Guard(policy, risk, redactOutput, idempotent)` | function | authorization policy |
| `@RateLimit(calls, perSeconds, scope, burst)` | function | token bucket |
| `@TextLimit(min, max)` | String param | length bounds |
| `@IntRange(min, max)` | Int/Long param | numeric bounds |
| `@Pattern(regex)` | String param | format check |
| `@OneOfVal(values)` | String param | enum-like whitelist |
| `@Sensitive(kind)` | param | marks PII, feeds the confirm dialog |

Policies:

- `ALWAYS_DENY` — the function is never reachable from an agent
- `CONFIRM_EACH` — user approves every call
- `CONFIRM_ONCE` — approval remembered for `consentTtlMillis` (default 7 days); **does not apply to `Risk.CRITICAL`**
- `ALLOW` — pass through, still rate-limited, still audited

---

## Design decisions

**The library imports nothing from `android.app.appfunctions.*`.**

That API is alpha. It has already removed components between alpha releases. If Guard depended on those types, every platform change would break the library. Instead Guard operates on `String functionId` + `Bundle args` — the two things that are stable across every version. Platform churn is absorbed by *your* service, in one line.

**Audit records never contain argument values.**

Only a SHA-256 digest of the argument *shape*, plus a count of redacted PII. A security library that logs raw personal data is a new vulnerability, not a fix.

**`CONFIRM_ONCE` never applies to `CRITICAL` risk.**

Convenience must not be able to make a destructive action permanent.

**Fail closed on misconfiguration.**

If a function needs confirmation and no `ConfirmationHandler` is installed, the call is denied. Not allowed, not ignored — denied.

**Degrade loudly.**

If the KSP-generated table is missing (misconfigured build), validation is skipped but every audit record is tagged `degraded = true`. Silent failure is worse than a crash.

---

## Field notes

What actually broke during week 1, written down while it still hurt: [给 Android 的 AI 后门装锁：第一周踩坑记录](docs/blog/w1-field-notes.md) (中文)

---

## Requirements

- `compileSdk 37` (AGP 9.1+, Gradle 9.3.1+)
- `minSdk 26` — the library itself is just `Bundle` + `Binder`, it runs anywhere; **AppFunctions itself requires a device on API 36**
- Kotlin 2.x
- KSP for annotation processing

```kotlin
// same coordinates as in Install above
implementation("io.github.wang-sunyang:guard-core:0.1.0-alpha5")
ksp("io.github.wang-sunyang:guard-ksp:0.1.0-alpha5")
```

Library modules are published to Maven Central — resolve them straight from `https://repo1.maven.org/maven2/`, no JitPack, no local build. `0.1.0-alpha5` (the first release under the new groupId) is pending; see [docs/PUBLISHING.md](docs/PUBLISHING.md) for the release process and the coordinate switch checklist.

---

## Status

**alpha — not production ready.**

Honest state of things:

- [x] Core interception chain, policy engine, rate limiter, redactor, audit, error protocol
- [x] KSP processor generating validators + metadata
- [x] CI on GitHub Actions — green build, unit tests, sample APK artifact
- [x] `sample/` — runnable demo app covering all four policies, rate limiting, redaction, audit viewer
- [x] `guard-ui` — drop-in confirmation dialog (`DialogConfirmationHandler`), used by `sample` itself
- [x] `guard-cli` — audit export to CSV / JSONL + summary, smoke tested in CI
- [x] Maven Central — `0.1.0-alpha4` of `guard-core` / `guard-ksp` / `guard-audit` / `guard-ui` is published and downloadable from `repo1.maven.org` (GPG signed, sources + javadoc attached). That release predates the groupId switch; `0.1.0-alpha5` will be the first under `io.github.wang-sunyang`. Process: [docs/PUBLISHING.md](docs/PUBLISHING.md)
- [x] **End-to-end verification on a real device** — 2026-11-16, Android 17 (API 37): all four functions are indexed by the platform, `listNotes` passes through, `deleteNote` / `exportNotes` return `USER_DENIED` first and execute on the second (confirmed) attempt, `wipeAllNotes` returns `FUNCTION_BLOCKED`. Confirmation chain proven on both branches. Remaining manual items (dialog appearance, dialog teardown on timeout, process-kill audit reset, disabled-overlay fallback, background invocation, AppFunction toggle off): [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md)

CI: [`build.yml`](.github/workflows/build.yml) runs on every push to `main` — assembles all four library modules plus `sample` / `guard-cli`, runs unit tests, smoke tests the CLI, verifies publication metadata. Details: [docs/CI.md](docs/CI.md)

If you're here early: the interesting part is the *policy surface*, not the code volume. Feedback on the annotation design is more useful than bug reports right now.

---

## Prior art check

As of 2026-11-24 (search re-run; the previous check was in early November 2026) I could not find an existing library that does per-function authorization / validation / rate limiting / audit for AppFunctions. GitHub search for `appfunctions` returns ~72 repos, almost all single-digit stars, mostly samples and one UI-automation tool (`anyfn`) that solves a different problem — it drives the UI, it doesn't guard the API surface. The official sample has a few hundred stars and no policy layer.

If you know of prior art, open an issue and I'll link it here. That's more valuable than pretending to be first.

Background reading on the risk model: the AppFunctions security analysis covering privacy leakage, isolation bypass, and LLM integrity attacks on Android 16.

---

## Roadmap

| Week | Goal | State |
|---|---|---|
| 1 | Run it end-to-end on a device, write up what actually broke | done for phase B (B1–B3, B4′/B5′ green on Android 17, 2026-11-16); phase A manual items still open — [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md) |
| 2 | `guard-ui` confirmation sheet + audit viewer | dialog done; audit viewer still lives in `sample` |
| 3 | `guard-cli` export, policy lint (`afg check`) | export done; `afg check` not started |
| 4 | Maven Central, v0.1.0 | `0.1.0-alpha4` published (first device-verified release); `v0.1.0` waits on the remaining manual items |

---

## License

Guard is **licensed per module**, not as one blob:

| Module | License | Full text |
|---|---|---|
| `guard-core`, `guard-ksp`, `guard-ui`, `guard-mcp`, `sample/` | AGPL-3.0-only | [LICENSE.AGPLv3](LICENSE.AGPLv3) |
| `guard-audit`, `guard-cli` | Apache-2.0 | [LICENSE.Apache-2.0](LICENSE.Apache-2.0) |

`guard-mcp` and `sample/` sit on the AGPL side because both *link* the enforcement layer, not because of a preference: `guard-mcp` compiles `guard-core`'s `PolicyEngine` / `RateLimiter` sources into its own jar, and `sample/` depends on `guard-core` / `guard-ui` / `guard-ksp`. Every artifact built from either module is therefore governed by AGPL-3.0 as a whole. Note §13: if you run `guard-mcp` as a network service, users of that service are entitled to the corresponding source — the "closed-source integration needs a commercial licence" framing does not apply here.

Why the split: the enforcement layer (`guard-core`, its KSP processor, its confirmation dialog) stays copyleft so a fork of the policy engine stays open. The embeddable pieces that do not carry the enforcement layer — the audit model/exporter and the CLI — stay permissive, so a tool can read Guard's audit format without inheriting the copyleft. That is the whole permissive list: a module qualifies only if a downstream consumer can use it without compiling or linking `guard-core`. `guard-mcp` and `sample/` do compile or link it, so they do not qualify.

**Other licensing arrangements.** If the terms above do not fit your product — closed source, app-store conditions, an internal AGPL ban — ask: open an issue titled `commercial license`, or use the contact in [AUTHORS.md](AUTHORS.md). Whether an alternative is available depends on the copyright holder's willingness to talk, not on terms promised in advance. Why the licences were chosen this way, and what would change them, is recorded in [docs/LICENSING.md](docs/LICENSING.md).

**Releases before the split.** Everything published up to and including `0.1.0-alpha4` was released under MIT and remains MIT for anyone who already has it. The per-module split starts at `0.1.0-alpha5`.

---

*Guard is not affiliated with Google. AppFunctions is an Android platform API; this is an independent community library.*
