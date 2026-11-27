# Licensing Policy

**AppFunction Guard** — per-function authorisation for Android 16 AppFunctions.

Document version: `1.0.0` · Date: 2026-11-27 · Status: **in effect**

This document records which licence applies to what, the reasoning behind it, and the
conditions under which that will change. It does **not** grant rights — the licence texts
do (`LICENSE.AGPLv3`, `LICENSE.Apache-2.0`). It is **not legal advice**; contracts,
jurisdiction and tax questions need a practising lawyer.

---

## 0. The short version

1. **A licence follows the revenue model, not the importance of the code.** Code that
   must be *linked* to enforce policy is the thing a competitor would fork — that is a
   copyleft question. Code that only *reads* or *reports* is distribution surface — that
   is a permissive question.
2. **Two things are withheld from every licence**: the trademark, and the right to offer
   different terms to a given party.
3. **Adoption first, monetisation later.** At zero external adopters, the highest-value
   move is not a tighter licence; it is a lower barrier. Licence tightening is reversible
   only in the direction that nobody wants (permissive → copyleft requires every
   copyright holder to agree), so the starting point stays the looser one.
4. **Content is the durable asset.** The enforcement rule set, policy templates and audit
   data sets are worth more as separately licensed content than as a licensing argument
   buried in source headers.

---

## 1. Current licence map

| Path | Licence | Why |
|---|---|---|
| `guard-core`, `guard-ksp`, `guard-ui`, `guard-mcp`, `sample/` | **AGPL-3.0-only** | compile or link the enforcement layer (`PolicyEngine` / `RateLimiter`) |
| `guard-audit`, `guard-cli` | **Apache-2.0** | usable without compiling or linking `guard-core` |

A module qualifies for the permissive side only if a downstream consumer can use it
without compiling or linking the enforcement layer. `guard-mcp` and `sample/` do compile
or link it, so they do not qualify — their licence follows from the dependency
direction, not from a preference.

The same table appears in `README.md` and `README.zh-CN.md`; if the two ever disagree,
**this file wins**, and the disagreement is a bug to be fixed.

> **§13, stated precisely.** AGPL-3.0 §13 applies when users interact with the program
> *over a network*. `guard-mcp` speaks MCP over stdio as a local child process, which
> does not trigger §13. The clause becomes relevant only if someone exposes it as a
> hosted service — and that hosting form, not the library, is what this policy intends to
> keep strict.

---

## 2. Decisions taken on 2026-11-27

| # | Decision | Choice | Reasoning |
|---|---|---|---|
| 1 | Long-term licence for the enforcement layer | **Move to Apache-2.0** (staged) | AGPL deters exactly the segment that would pay for a different arrangement: closed-source integrators. Loosening the adoption layer is what makes a commercial option sellable later. |
| 2 | Copyright concentration | **DCO sign-off** | Cheapest possible at zero external contributors; keeps the project able to offer non-AGPL terms later; far less friction than a CLA. |
| 3 | Hosted / server-side form | **Separate repository, later** | The hosted gateway and audit backend are what can be charged for annually; strict licensing belongs on that form. Splitting now would cost maintenance for no revenue. |

Decision 1 is **staged on purpose** — see §4. The licence map in §1 is still the map in
force today.

---

## 3. What "staged" means, and what it does not

"Staged" is not a promise to loosen everything eventually. It is a statement that the
enforcement layer's licence changes when a specific event happens, not when the mood
changes:

- **Already published releases are not retracted.** Everything up to `0.1.0-alpha4` was
  MIT and stays MIT for anyone who has it.
- **Loosening is one-way in practice.** `guard-core` can go Apache-2.0 while the copyright
  holder is a single person. It cannot go back to AGPL once contributors hold pieces of
  the copyright — which is precisely why §5 exists.
- **The strict tier moves sideways, not away.** Tightening relocates to the hosted form
  (§4, Stage 2). It never gets applied retroactively to code already granted under a
  looser licence.

---

## 4. Roadmap

### Stage 0 — now (cost ≈ 0, no licence change)

- [x] Publish this policy.
- [x] Require DCO sign-off on contributions (§5; recorded in `AUTHORS.md`).
- [x] Replace "closed-source integration needs a commercial licence" phrasing wherever it
      still appears with the neutral form: *other licensing arrangements are available on
      request*.
- [x] Keep the enforcement-layer licence as-is.

Nothing in Stage 0 changes what anyone may do with the code today.

### Stage 1 — trigger: first external adopter, or a commercial enquiry

When either happens:

- Switch `guard-core` / `guard-ksp` / `guard-ui` to **Apache-2.0**; the dependent modules
  (`guard-mcp`, `sample/`) follow, since their copyleft status was derived from that
  dependency.
- Sync in one pass: SPDX headers, `README.md` / `README.zh-CN.md` tables, `AUTHORS.md`,
  `CHANGELOG.md`, and the POM licence assertions in **both** CI copies
  (`.github/workflows/build.yml` and `ci/build.yml`).
- Record the change per version in `CHANGELOG.md`; state which version first carries the
  new terms.

Cleanest point to do this is a version boundary — `0.1.0-alpha5` has not been published,
so it can be the first release under either map.

### Stage 2 — trigger: an enterprise asks for a contract

- Move the hosted gateway / audit backend / policy hosting into its own repository under a
  strict licence (AGPL-3.0-only, or BUSL-1.1 if the shape demands it — note BUSL is not
  OSI-approved and must be labelled as such).
- That requires contract templates, pricing and a support SLA. Until those exist, there is
  nothing to sell and no reason to split.

---

## 5. Contributions: DCO, not a CLA

Every commit must carry a `Signed-off-by` line matching the commit author
([DCO 1.1](https://developercertificate.org/)). `git commit -s` adds it.

- Contributors **keep** copyright in their contributions.
- Contributors grant the project a perpetual, worldwide, non-exclusive, royalty-free
  licence to use, modify and redistribute the contribution as part of this project.
- No copyright assignment is requested. A CLA may be introduced later **only** for
  contributions after that point; earlier contributions stay under DCO terms.

Why sign-off rather than a formal CLA: at zero external contributors the cost of
introducing DCO is near zero, while a CLA at the same moment sends the wrong signal to
the first person considering a patch. DCO keeps the paper trail that a future commercial
arrangement will need, without a signature ceremony.

---

## 6. Commercial licensing and other arrangements

If the current terms do not fit — closed source, app-store conditions, internal policy,
an AGPL ban — ask. Open an issue titled `commercial license`, or use the contact in
[`AUTHORS.md`](../AUTHORS.md).

Availability of an alternative arrangement is stated as a fact about the copyright
holder's willingness to talk, not as an offer of terms.

---

## 7. What this policy will not do

- Will not retract a licence already granted for a published version.
- Will not promise dual licensing before copyright is concentrated.
- Will not mix AGPL and BUSL inside one repository without labelling the boundary.
- Will not treat GitHub Sponsors as a revenue path at this stage.
- Will not split the repository before there is a contract to put in the new one.

---

## 8. Change log

| Date | Version | Change |
|---|---|---|
| 2026-11-27 | 1.0.0 | Policy written; three decisions recorded; DCO adopted; supersedes `docs/LICENSING-STRATEGY.md` (proposal, now removed) |

---

## 中文摘要

**政策已生效。** 三项决策：① 防护层长期转 Apache-2.0（分阶段，当前许可不变）② 版权归集用 DCO sign-off（提交加 `Signed-off-by`，不签 CLA，版权不转移）③ 服务化形态将来单独立仓并挂严格许可。

**许可跟随收费形态，而非代码重要性。** AGPL 会挡掉"愿意付费但必须闭源集成"的客群，而这正是商业授权该卖的人；先降门槛换采用，再谈变现。

**AGPL §13 对 `guard-mcp` 不触发** —— 它是 stdio 本地子进程，不是"通过网络交互"。真正该严格的是托管服务形态。

**已发布版本许可不撤回**：`0.1.0-alpha4` 及以前是 MIT，永久是 MIT。

**内容才是持久资产**：防护规则库、策略模板、审计数据集比在源码头里做许可文章更值钱。

本文不构成法律意见；涉及合同、管辖与税务需执业律师确认。
