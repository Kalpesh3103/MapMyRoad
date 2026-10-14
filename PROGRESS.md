# Progress

**Product:** Road Quality Mapper (working title)
**Last updated:** 2026-10-14
**Related:** [TASKS.md](TASKS.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md), [DECISIONS.md](DECISIONS.md), [AGENTS.md](AGENTS.md)

This file is the running record of where the project stands. Update it after every work session, every merged task and every milestone gate. Keep entries short and factual. Newest entries go at the top of the log.

---

## 1. Current status

| Item | Value |
|---|---|
| Phase | Planning complete, implementation not started |
| Current milestone | M0 Foundations |
| Current task | None in progress |
| Next task | M0-1 Choose app name and Android package ID |
| Blockers | None |
| Next release target | v1 (personal mapper) after M0 to M3 |

## 2. Milestone tracker

| Milestone | Status | Started | Finished | Exit gate met? | Notes |
|---|---|---|---|---|---|
| Planning docs (16 files) | Done | 2026-10-13 | 2026-10-14 | Yes | See log |
| M0 Foundations | Not started | | | No | |
| M1 Detection data spike | Not started | | | No | |
| M2 v1 build | Not started | | | No | |
| M3 v1 closed test and release | Not started | | | No | |
| M4 v2 backend | Not started | | | No | Can overlap with M3 |
| M5 v2 Android | Not started | | | No | |
| M6 v2 hardening and release | Not started | | | No | |
| M7 v3 navigation and alerts | Not started | | | No | |
| M8 v4 civic tools | Not started | | | No | |

Task-level status lives in TASKS.md. This table only records milestone-level facts.

## 3. Key metrics (fill in as they become measurable)

| Metric | Target | Latest | Measured on | Notes |
|---|---|---|---|---|
| Detector recall (severe) | at least 70% | not measured | | M1-6 |
| Detector precision (severe) | at least 70% | not measured | | M1-6 |
| Speed-breaker false alarms | under 1 per 10 km | not measured | | |
| Battery per hour of tracking | about 5% or less | not measured | | M1-7 |
| Crash-free sessions | at least 99% | not measured | | |
| Trip loss rate (service killed) | track | not measured | | |
| Mapped km (pilot) | track | 0 | | |
| Active contributors | track | 0 | | |
| Backend p95 latency (tiles, routes) | under about 3 s routes | not measured | | M6-2 |
| Last successful backup restore drill | monthly | never | | |

## 4. Risk watchlist

Review at every milestone gate. Mark each risk Open, Mitigated, Realized or Closed.

| Risk | Status | Signal to watch | Notes |
|---|---|---|---|
| Detector accuracy too low | Open | M1 precision and recall | Decide scope after M1 |
| OEM battery managers kill tracking | Open | Trip loss rate in M2 field tests | |
| Foreground-service start from background blocked (auto-start) | Open | M2-15 spike | |
| Oracle free VM unavailable or ARM image gaps | Open | M0-9 | Fallback: local machine for the pilot |
| Map-matching quality on sparse windows | Open | M6-3 | |
| Alternates too similar for comfort routing | Open | M4-14 and M5-8 testing | |
| Ferrostar and Valhalla integration | Open | M4-18 spike | |
| Play review of location permissions | Open | M3 | Foreground-first design |
| Re-identification on low-traffic roads | Open | Visibility thresholds (M4-19) | |
| Free-tier changes | Open | Provider notices | Keep fallbacks documented |
| Scope creep | Open | Backlog growing faster than it closes | Hold each phase to its exit gate |

## 5. Open questions and decisions needed

| # | Question | Owner | Needed by | Status |
|---|---|---|---|---|
| 1 | Final app name, package ID and branding | Kalpesh | M0-1 | Open |
| 2 | Confirm ADR-0013 (no feedback while moving) and update PRD CM-4 wording | Kalpesh | Before M5 | Open |
| 3 | Confirm ADR-0014 (no certificate pinning initially) | Kalpesh | Before M4-20 | Proposed |
| 4 | Backend build tool: Maven or Gradle | Kalpesh | M4-5 | Open |
| 5 | Static analysis tools for Java and Kotlin (detekt, ktlint, Error Prone, SpotBugs) | Kalpesh | M0-3 | Open |
| 6 | Closed-testing requirement for the Play developer account (confirm current rule) | Kalpesh | M3-1 | Open |
| 7 | Research mode for raw sensor snippets | Kalpesh | After M1 | Open |
| 8 | First municipal complaint channels | Kalpesh | M8-3 | Open |
| 9 | Gating rule for the public heatmap | Kalpesh | M6-6 | Open |
| 10 | ODbL obligations for derived exports | Kalpesh | M8-6 | Open |
| 11 | Hindi and Marathi translation review plan | Kalpesh | M5-11 | Open |
| 12 | Legal review of privacy policy | Kalpesh | M6-4 | Open |

## 6. Environment and accounts checklist

Tick off as each is created. Do not write secrets here.

- [ ] GitHub repository (private) with branch protection
- [ ] Google Play Console developer account (one-time registration fee)
- [ ] Firebase project (Crashlytics and Analytics)
- [ ] Oracle Cloud account and Always Free ARM VM
- [ ] Domain or DNS name for the API
- [ ] Object storage bucket for backups (and encryption key stored offline)
- [ ] Uptime monitor account
- [ ] Privacy policy URL (GitHub Pages)
- [ ] Google Cloud project and service account for Play Integrity verification
- [ ] Two-factor authentication on every account above
- [ ] Offline vault for keys and recovery codes

## 7. Data and infrastructure facts (fill in when they exist)

| Item | Value |
|---|---|
| Repository URL | |
| App package ID | |
| API base URL | |
| VM region and shape | |
| OSM extract used (name and date) | |
| Valhalla version | |
| PostGIS version | |
| Last OSM refresh | |
| Last backup | |
| Last restore drill | |
| Current app version in production | |
| Current API version in production | |

## 8. Log

Newest first. Format: date, what changed, what is next. Link task IDs and ADRs.

### 2026-10-14
- Wrote the remaining nine planning documents: `UI_UX_GUIDELINES.md`, `SECURITY.md`, `CODING_STANDARDS.md`, `TESTING_STRATEGY.md`, `TASKS.md`, `AGENTS.md`, `DEPLOYMENT.md`, `DECISIONS.md`, `PROGRESS.md`. The full set of 16 documents is complete.
- New decisions recorded: ADR-0013 (no hazard feedback while moving), ADR-0014 proposed (no certificate pinning initially), ADR-0015 (Actuator on an internal port).
- New tasks added: M0-10, M2-19, M2-20, M3-7, M4-19, M4-20, M6-8 (see TASKS.md section 11).
- Small alignment edits made to earlier docs: API_SPEC.md section 5.13 (management port) and PRD CM-4 (feedback not while moving).
- Next: M0-1 (name and package ID), then M0-2 (repository).

### 2026-10-13
- Chose the idea: a passive road-quality mapper for Android, with route comparison by comfort and later in-app navigation, built on free tools.
- Wrote `PROJECT_OVERVIEW.md`, `PRD.md`, `ARCHITECTURE.md`, `TECH_STACK.md`, `API_SPEC.md`, `DATABASE_SCHEMA.md`, `IMPLEMENTATION_PLAN.md`.
- Also produced a spreadsheet of the free tool stack (`road-quality-app-free-stack.xlsx`).
- Next: finish the remaining documents.

## 9. Weekly review template

Copy into the log each week once implementation starts.

```
### YYYY-MM-DD (week review)
- Done: task IDs and one-line outcomes
- In progress: task IDs and state
- Blocked: what and why
- Metrics changed: which and to what
- Risks changed: which and why
- Decisions made: ADR numbers
- Next week: top three tasks
```

## 10. Milestone gate checklist template

Copy into the log when a milestone ends.

```
### Milestone Mx gate (YYYY-MM-DD)
- Exit gate criteria (from IMPLEMENTATION_PLAN.md):  met / not met, evidence
- Acceptance criteria from PRD:                     list and result
- Testing exit criteria (TESTING_STRATEGY.md s15):   met / not met
- Security items (SECURITY.md s17) for this release: met / not met
- Metrics recorded in section 3:                     yes / no
- Risks reviewed in section 4:                       yes / no
- Docs updated (API, schema, architecture, UI):      yes / no
- Go / no-go for the next milestone:                 decision and reason
```
