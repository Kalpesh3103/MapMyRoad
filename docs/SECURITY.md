# Security and Privacy

**Product:** MapMyRoad
**Status:** Draft v0.1
**Related:** [API_SPEC.md](API_SPEC.md), [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md), [ARCHITECTURE.md](ARCHITECTURE.md), [PRD.md](PRD.md), [DEPLOYMENT.md](DEPLOYMENT.md)

This document is a security design and checklist for a solo-developer project. It is not a penetration test report and not legal advice. Items marked *verify* depend on current law, store policy or vendor behavior and must be re-checked before release.

---

## 1. Principles

1. **Collect less.** Raw sensor data and full traces never leave the phone.
2. **Anonymous by design.** No accounts, no email, no phone number.
3. **Protect location data above all.** It is the most sensitive data in the system.
4. **Assume uploads are hostile.** Every field from a client is validated on the server.
5. **Fail safe.** If a check fails, drop the data rather than store it.
6. **Keep it simple.** Few components and few open ports mean a small attack surface.
7. **Document and rehearse.** Deletion, backup restore and incident response must work before they are needed.

## 2. Assets and data classification

| Asset | Class | Where | Why it matters |
|---|---|---|---|
| Trip history with full GPS tracks | Highly sensitive (personal location history) | Phone only (Room) | Reveals home, work, routines |
| Uploaded events and windows with device ID | Sensitive (location linked to a pseudonym) | Server, 90 days | Re-identification risk |
| `trip_token` | Linking key | Server, cleared after matching | Could link points of one trip |
| Device ID, refresh tokens | Credentials | Phone, server (hashed refresh tokens) | Impersonation, abuse |
| Aggregated hazards and road statistics | Public (aggregated) | Server, public tiles | Integrity and privacy of low-traffic roads |
| Manual reports and photos (v3) | Sensitive | Server, private | Photos can reveal people, plates, homes |
| Secrets (JWT key, DB password, Play service account, signing keys) | Critical | VM and GitHub secrets, offline backups | Full compromise |
| Backups | Same as the data they hold | Object storage | Often forgotten |
| Source code and CI | Integrity-critical | GitHub | Supply chain |
| Availability of the service | Important | VM | The app must still work offline (design goal) |

## 3. Threat actors

| Actor | Motivation | Capability |
|---|---|---|
| Curious or hostile user | Probe the API, poison the map | Modified app, scripts, emulators |
| Data scraper | Harvest location data or the whole hazard database | Automated requests |
| Re-identification attacker | Find where a specific person lives or travels | Public tiles over time, auxiliary knowledge |
| Disgruntled party | Fake hazards on a rival road or area, hide real ones | Multiple devices |
| Opportunistic attacker | Attack an exposed VM or dependency | Internet scanning, known CVEs |
| Lost or stolen phone | Read trip history | Physical access |
| Insider and mistakes (me) | Accidental leak of secrets or data | Repository, logs, backups |
| Legal and government requests | Disclosure of location data | Legal process |

## 4. Threat model

| # | Threat | Example | Mitigation | Phase |
|---|---|---|---|---|
| T1 | Home or workplace exposure through trip ends | Trip starts and ends at home | Trim first and last 500 m on the device before upload. Drop trips under about 1 km. Server also rejects points inside trim distance only if it can detect them (best effort). | v2 |
| T2 | Re-identification from sparse roads | A hazard on a cul-de-sac reveals one household's trips | Visibility thresholds: at least 2 devices and 3 samples (API_SPEC). **Higher threshold (3 devices) for residential, service, living-street and unclassified ways.** No per-device data in public APIs. | v2 |
| T3 | Linking trips across time | Same `trip_token` or device ID tracks a person | `trip_token` is random per trip and cleared after matching. Device ID removed from raw events after 90 days. Device ID never appears in any public response. | v2 |
| T4 | Fake devices flood the map | Scripts or emulators POST events | Play Integrity at registration, rate limits, plausibility checks, trust score, confirmation from several devices before high confidence | v2 |
| T5 | Targeted hazard poisoning | Many fake reports on a competitor's road | Per-device and per-area rate limits, trust-weighted confidence, anomaly detection on sudden spikes in one small area, manual review tools | v2 (review tools in M6) |
| T6 | Hiding real hazards | Bulk "fixed" or "dismiss" votes | One vote per device per hazard, location proximity check, trust weighting, fixed status needs multiple clean passes | v2 |
| T7 | Token theft or replay | Stolen access token reused | Short-lived (1 hour) JWTs, refresh token rotation with reuse detection, tokens in encrypted storage, HTTPS only | v2 |
| T8 | Registration abuse | Mass device creation | Play Integrity, 10 per hour per IP, challenge nonces with short TTL | v2 |
| T9 | Scraping of hazards and tiles | Download the whole dataset | Tile and hazard rate limits, bbox size limits, no bulk list endpoint, caching. Public data is aggregated by design, so scraping is low impact. | v2 |
| T10 | Injection | SQL injection through query parameters | Parameterized queries only, strict validation, bbox and numeric parsing, no string-built SQL | v2 |
| T11 | Oversized or malformed payloads | Gzip bombs, huge arrays | Size limits before and after decompression, array caps (500 events, 1000 windows), timeouts | v2 |
| T12 | SSRF through geocoding or routing proxies | Attacker controls upstream URLs | Fixed upstream hosts from configuration only, no user-supplied URLs, strict outbound allowlist | v2 |
| T13 | Photo upload abuse (v3) | Malware or illegal images | Private storage, size and type checks, re-encode images server-side, strip EXIF (GPS and device data), no public display | v3 |
| T14 | Server compromise | Exposed ports, weak SSH, vulnerable image | Minimal open ports, key-only SSH, updates, non-root containers, secrets outside images, DB not published | v2 |
| T15 | Backup leak | Backup bucket misconfigured | Encrypted backups, private bucket, access keys scoped to one bucket, restore tests | v2 |
| T16 | Supply-chain attack | Malicious dependency or CI action | Pinned versions, Dependabot, lockfiles, actions pinned by commit SHA, secret scanning, minimal CI permissions | v1 |
| T17 | Lost or stolen phone | Read trip history from app data | Android sandbox and device encryption. `allowBackup` disabled. Optional app lock (later). | v1 |
| T18 | Malicious app on the same phone | Reads our data or intercepts intents | No exported components except the launcher activity, internal-only service, no sensitive data in intents or logs, scoped storage | v1 |
| T19 | Man-in-the-middle | Hostile Wi-Fi | HTTPS with modern TLS, cleartext disabled in the network security config. Certificate pinning deferred (ADR-0014). | v2 |
| T20 | Denial of service | Request floods | Rate limits at Caddy and in the app, small VM limits, graceful degradation, app works offline | v2 |
| T21 | Legal request for data | Authority asks for a user's location | Minimal data held, retention limits, no identity data to link, documented handling process (section 15) | v2 |
| T22 | Accidental exposure in logs | Coordinates or tokens logged | Redacting types, logging rules (CODING_STANDARDS.md), log review, short log retention | v1 |

## 5. Authentication and authorization

Summary of the design in API_SPEC.md:

- **Registration:** challenge nonce, Play Integrity token, device record, access token (JWT, about 1 hour) and refresh token (opaque, 180 days, rotated on each use; longer than the 90-day device ID detachment so a re-registration never strands deletable data, PRD PV-3).
- **Verification:** the server verifies the integrity verdict (genuine app from Google Play, genuine device) and the nonce. Verdict requirements are configurable. Weaker verdicts may be accepted with a lower trust score rather than rejected (to avoid locking out legitimate devices on unusual hardware). *Verify* current Play Integrity API behavior and quotas.
- **JWT:** signed with a server secret and a key ID (`kid`) so keys can rotate with overlap. Claims: device ID, issued-at, expiry, scope. No personal data.
- **Refresh tokens:** random 256-bit values, stored only as SHA-256 hashes, rotated on each use. Reuse of a rotated token revokes the device's tokens and forces re-registration.
- **Authorization:** one role (`device`). A device can only read and delete its own data (`/devices/me`, its own reports). Public endpoints expose only aggregated data.
- **Admin access:** there is no admin API in v2. Operations happen through SSH and database roles. If an admin UI is ever added, it needs its own authentication (separate from device tokens) and audit logging.

## 6. Data protection

### 6.1 In transit

- HTTPS only. Caddy terminates TLS with automatic certificates. TLS 1.2 or higher.
- HSTS enabled on the API domain.
- Cleartext traffic disabled in the Android network security config.
- Tile and geocoder calls from the app to third-party hosts use HTTPS and carry no identifiers beyond what a normal request carries. The base map host sees the user's IP and viewed area (state this in the privacy policy).

### 6.2 At rest

| Where | Control |
|---|---|
| Phone | App-private storage (Android sandbox, file-based encryption on supported devices). Tokens encrypted with an Android Keystore-backed key. `allowBackup=false` and data extraction rules exclude all app data from cloud backup and device transfer. |
| Server disk | Encrypted block volume where the provider offers it. *Verify* for the chosen VM. |
| Database | Not exposed outside the Docker network. Separate roles (`rq_migrator`, `rq_app`, `rq_readonly`). |
| Backups | Encrypted before upload (for example with `age` or `gpg`), private bucket, scoped access keys. The decryption key is stored offline, separate from the VM. |
| Photos (v3) | Private bucket, accessed only through the backend, EXIF stripped, deleted with the report or device. |

### 6.3 Data minimization and retention

See DATABASE_SCHEMA.md section A10. Summary: device ID removed from raw events after 90 days, trip tokens cleared after matching (24 hours at most), raw rows deleted after 365 days, aggregated data kept without identifiers, inactive devices deleted after 12 months, deletion on request.

### 6.4 Privacy-preserving publication rules

Public tiles and exports must follow these rules:

- Minimum devices and samples per segment (section 4, T2).
- Never publish raw events, timestamps finer than a day, or device-related fields.
- Round published coordinates to a precision appropriate for road features (a few meters), never to sub-meter.
- Exclude segments flagged as private or access-restricted in OSM (`access=private`, gated communities) when those tags exist.
- Review any new export format against these rules before release (M8-6).

## 7. Abuse and data integrity

- **Plausibility checks:** speed 0 to 200 km/h, timestamps within a window, coordinates inside the region, acceleration scores within bounds, window length 20 to 120 m.
- **Rate limits:** per device and per IP (API_SPEC section 4), plus per-area caps to blunt targeted poisoning.
- **Trust score:** starts at 0.5. Rises with long, consistent, corroborated contributions. Falls with rejected items, implausible patterns or contradictions with many other devices. Used to weight confidence, never shown to users.
- **Corroboration:** a hazard is shown only after enough independent devices report it (confidence threshold 0.3 for display and more for alerts).
- **Anomaly monitoring:** daily job flags areas with sudden spikes in new hazards or fixed votes for manual review.
- **Kill switches:** a config flag to stop accepting uploads and a way to block a device or revoke its tokens.
- **Reversibility:** because raw events are kept for the retention window, a poisoning event can be removed and aggregates recomputed.

## 8. Android app security checklist

- [ ] `android:allowBackup="false"` and `dataExtractionRules` / `fullBackupContent` exclude everything.
- [ ] Only the launcher activity is exported. Tracking service, receivers and providers are not exported.
- [ ] Network security config: cleartext disabled, system trust anchors only.
- [ ] No secrets, API keys or signing material in the app or repository. (The app has no secret that cannot be extracted from it. Treat any bundled key as public.)
- [ ] R8 shrinking and obfuscation enabled in release. Mapping files uploaded to Crashlytics.
- [ ] Release builds are not debuggable. Debug logging and the sensor logger are debug-only (M1-1 logger never ships).
- [ ] Tokens stored encrypted (Keystore-backed key). Cleared on data deletion.
- [ ] Logs contain no coordinates, tokens or device IDs in release builds.
- [ ] Deep links, if any are added, validate all inputs.
- [ ] WebView is not used. If it is ever needed, disable JavaScript and file access by default.
- [ ] Analytics events carry no precise location (PRD section 9).
- [ ] Permissions are the minimum set (TECH_STACK.md section 3.3).
- [ ] Foreground service notification does not leak data on the lock screen (use public visibility with generic text).
- [ ] Play Integrity checks run at registration and before upload batches when the token is near expiry.

## 9. Backend and infrastructure security checklist

**Application**
- [ ] All SQL uses named parameters. No string concatenation, including `ORDER BY` and table names.
- [ ] Bean Validation on every DTO, with explicit maximums.
- [ ] Request size limits at Caddy and in Spring. Decompressed size checked.
- [ ] Problem-detail errors reveal no stack traces or internal details.
- [ ] Security headers on API responses (`X-Content-Type-Options`, `Cache-Control` policies, HSTS). CORS is disabled (no browser clients).
- [ ] Outbound calls only to configured upstreams with timeouts.
- [ ] Dependency vulnerability scan in CI (OWASP Dependency-Check or equivalent, plus Dependabot alerts).
- [ ] Actuator on a separate management port, reachable only on the Docker network (DEPLOYMENT.md). Never public.

**Host**
- [ ] SSH: key-only, root login disabled, non-default user, firewall-restricted to my IP where practical.
- [ ] Only ports 80 and 443 open publicly (and SSH as above). Database and Valhalla ports are not published.
- [ ] Automatic security updates enabled. Reboot plan documented.
- [ ] fail2ban or equivalent for SSH.
- [ ] Containers run as non-root, with read-only filesystems where possible, no privileged mode, resource limits set.
- [ ] Docker images pinned by version (and digest for production).
- [ ] Time sync (NTP) enabled, since token validation depends on time.

**Data**
- [ ] Database roles split (`rq_migrator`, `rq_app`, `rq_readonly`). The app role cannot alter schema.
- [ ] Backups encrypted, restore tested monthly, access keys scoped.
- [ ] Retention job verified in production (spot-check queries).
- [ ] Deletion flow verified (PV-3 acceptance test).

## 10. Secrets management

| Secret | Where it lives | Rotation |
|---|---|---|
| JWT signing key(s) | VM environment file (root-only permissions) | Every 6 months, with overlapping `kid`s |
| Database passwords | VM environment file | On suspected exposure and yearly |
| Play Integrity service account key | VM, restricted file permissions | Yearly |
| Backup encryption key | Offline storage, not on the VM | Yearly, keep old keys to decrypt old backups |
| Object storage keys | VM environment file, scoped to one bucket | Yearly |
| Android upload key (keystore) | Offline backup plus GitHub encrypted secret if CI signs | Never rotate casually, use Play App Signing so a lost upload key is recoverable by request |
| Firebase config (`google-services.json`) | Repository is acceptable (not a secret), but restrict API keys by app signature and package name | As needed |
| GitHub tokens | Short-lived, minimum scope | Review quarterly |

Rules: no secrets in Git (a secret scanner runs in CI and as a pre-commit hook), no secrets in logs, no secrets in Docker images, `.env` files are git-ignored with a committed `.env.example`. If a secret leaks, rotate it first and then investigate.

## 11. Supply chain and CI

- Enable Dependabot or Renovate for Gradle, Maven, Docker and GitHub Actions.
- Pin GitHub Actions by full commit SHA. Use minimal `permissions:` per workflow.
- Protect the main branch: required status checks, no force pushes.
- Require two-factor authentication on GitHub, Google (Play Console and Firebase), Oracle Cloud, Cloudflare and the domain registrar.
- Verify checksums for downloaded data tools and OSM extracts where available.
- Prefer widely used, maintained libraries. Record every new dependency in the PR with a one-line reason (CODING_STANDARDS.md).
- Review the licenses of new dependencies (TECH_STACK.md section 8).

## 12. Logging, monitoring and detection

| What | How |
|---|---|
| Application logs | JSON logs with request ID. No coordinates, tokens, device IDs or raw bodies. |
| Access logs | Caddy logs with IP truncation or short retention (for example 14 days). *Decide* retention with the privacy policy. |
| Security events | Failed integrity checks, refresh token reuse, rate-limit hits, rejected uploads by reason, sudden spikes per area. Counters in Micrometer. |
| Alerts | Uptime monitor, disk usage, error-rate spike, repeated auth failures, backup failure |
| Review | Weekly skim of security counters during the pilot, monthly afterwards |

## 13. Incident response

Severity levels:

| Level | Example | Target response |
|---|---|---|
| SEV1 | Confirmed leak of personal location data, server compromise, leaked signing key | Immediately. Contain the same day. |
| SEV2 | Suspected abuse of the API, backup exposure risk, vulnerable dependency actively exploited | Within a day |
| SEV3 | Minor vulnerability, misconfiguration without exposure | Within a week |

Steps:
1. **Contain:** disable uploads (kill switch), revoke tokens, block the source, take the host offline if needed.
2. **Preserve:** snapshot logs and disk before cleaning up.
3. **Assess:** what data, how many devices, which period, how exposed.
4. **Eradicate and recover:** patch, rotate every secret that might be affected, restore from a clean backup if necessary.
5. **Notify:** affected users and authorities as required. *Verify* current breach-notification obligations under Indian law (including the Digital Personal Data Protection Act, 2023 and its rules) and Google Play policy. A published support contact and a way to report a vulnerability (a `SECURITY` contact in the repository and Play listing) are required before v2.
6. **Learn:** write a short post-incident note in `docs/incidents/` and update this document and the checklists.

Keep a one-page **runbook** with contact details, where secrets live and how to run the kill switch (DEPLOYMENT.md has operational runbooks).

## 14. Privacy compliance checklist

- [ ] Privacy policy is public, in plain language, and matches the app's real behavior and the Data safety form (PRD section 8.1).
- [ ] The policy covers: what is collected, why, retention, sharing (base map host, Photon, Firebase, Google Play services), deletion, contact, children's policy (the app is not directed at children), changes.
- [ ] In-app consent before the first upload, with a clear opt-out.
- [ ] Deletion works in the app and via a web form or email for users who uninstalled (Play requires a data deletion method for apps that create accounts. This app has none, but offer a contact route anyway).
- [ ] Data safety form completed and kept current at every release.
- [ ] Background location declaration and demo video, if auto-start ships.
- [ ] OSM attribution shown.
- [ ] Legal review of the privacy policy before v2 (not legal advice here). *Verify* the current status and rules under the Digital Personal Data Protection Act, 2023.
- [ ] Third-party processors listed (Firebase, hosting provider, object storage) with their data locations understood.

## 15. Handling legal and government requests

- Hold as little as possible: aggregated data has no identifiers, raw events lose their device ID after 90 days, and the system holds no names, emails or phone numbers.
- Any request is handled in writing, verified, scoped to the minimum, and logged privately.
- Seek legal advice before responding. Do not volunteer data.
- Consider a short transparency note in the policy once there is real usage.

## 16. Security testing

| When | What |
|---|---|
| Every commit | Lint, unit tests, dependency scan, secret scan |
| Every release | Android manifest review, permission review, `adb` backup test (should extract nothing), release logs check (no coordinates) |
| Before v2 launch | Manual API abuse tests (TESTING_STRATEGY.md section 9), rate limit tests, token replay and reuse tests, SQL injection probes on all parameters, oversized payload tests, SSRF probes on geocoding inputs, re-identification check on low-traffic roads, backup restore and deletion drills |
| Before v3 launch | Photo upload tests (malformed files, EXIF stripping, size), navigation endpoint abuse tests |
| Yearly | Full checklist run, secret rotation, dependency major upgrades, review of this document |
| Optional | A trusted peer review or a bounded external security review of the backend and the app before public launch |

## 17. Release security gate

Before submitting any release:
- [ ] Sections 8 and 9 checklists pass for the components in this release.
- [ ] No high or critical open dependency vulnerabilities without a documented exception.
- [ ] No secrets in the repository history (scanner clean).
- [ ] Privacy policy, Data safety form and in-app text agree with each other.
- [ ] Deletion and retention verified on the release candidate.
- [ ] Kill switch tested.

## 18. Known gaps and accepted risks

| Gap | Why accepted for now | Revisit |
|---|---|---|
| No certificate pinning | Operational risk of lockout outweighs benefit for anonymous, aggregate data (ADR-0014) | If abuse or interception evidence appears |
| Single VM, no high availability | Cost. The app works offline. | When upgrade triggers fire |
| Trust scoring is heuristic | No ground truth yet | After pilot data review (M6-3) |
| Play Integrity can fail on legitimate unusual devices | Prefer lower trust over lockout | Monitor rejection reasons |
| Public base-map and geocoder hosts see IP and viewed area | Free-tier choice | Self-host when feasible |
| No formal external audit | Cost | Before wide launch if the user base grows |
| Re-identification risk cannot be reduced to zero | Aggregation thresholds reduce it | Review thresholds with pilot data |
