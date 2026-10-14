<!--
Title: Conventional Commit style with the task ID, for example
feat(detector): add speed normalization (M2-4)
Keep PRs under about 400 changed lines (excluding generated code and fixtures).
-->

## Task ID

<!-- For example M2-4. One task per PR. -->

## What and why

<!-- What changed and why. Link the PRD requirement IDs and doc sections you followed. -->

## How tested

<!-- Commands run and their result (only commands you actually ran), new tests, manual checks, devices used. -->

- [ ] `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest assembleDebug`

## Docs updated

<!-- API_SPEC, DATABASE_SCHEMA, ARCHITECTURE, UI_UX_GUIDELINES, PRD, privacy policy (site/), or "None". -->

## Privacy and security impact

<!--
Does this change what is collected, stored, uploaded, logged or retained? Touch permissions,
trimming, retention, deletion, tokens or auth? If yes, explain and link the tests that guard it.
Otherwise write "None".
-->

## Screenshots

<!-- Required for UI changes (light and dark, large font if relevant). Delete if not applicable. -->

## Dependencies added or changed (with reason)

<!-- Name, version, license, what it does and why existing tools are not enough. Or "None". -->

## Checklist

- [ ] Task ID in the title or body
- [ ] What and why described, with screenshots for UI
- [ ] Tests added and passing, lint clean
- [ ] Docs updated (API, schema, architecture, UI) when affected
- [ ] No sensitive data (precise coordinates, tokens, device IDs) in logs, analytics or tests
- [ ] Strings externalized, accessibility labels added
- [ ] New dependencies explained
- [ ] No unrelated reformatting
- [ ] `TASKS.md` status and `PROGRESS.md` entry updated
- [ ] ADR drafted if a decision changed
