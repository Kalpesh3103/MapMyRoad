# MapMyRoad

MapMyRoad is an Android app (package `com.mapmyroad`) that detects potholes and rough road
surfaces passively with the phone's motion sensors and GPS, shows the road quality of your own
trips on a map and, in later versions, builds a crowdsourced road-quality map that recommends
smoother routes. It is privacy-first (no account, raw sensor data and full GPS traces never leave
the phone) and uses free and open-source tools.

**Status:** milestone M0 (foundations) in progress. See [`docs/PROGRESS.md`](docs/PROGRESS.md) and
[`docs/TASKS.md`](docs/TASKS.md).

## Repository layout

```
android/app/           Android app (Kotlin, Jetpack Compose)
android/sensorlogger/  Debug sensor logger module (M1)
backend/               Spring Boot service (Java 21, Gradle), v2 backend (M4)
infra/                 Docker Compose (dev and prod), Caddy, backup and deploy scripts
tools/                 Python analysis scripts, notebooks and trace converters
docs/                  Planning documents (start with docs/AGENTS.md)
site/                  Static pages for GitHub Pages (privacy policy draft)
.github/               CI workflows, Dependabot, PR and issue templates
```

## Quick start (Android)

Requirements: JDK 17 or newer and the Android SDK (Android Studio installs both).

- **Android Studio:** open the `android/` directory (not the repository root).
- **Command line:**

  ```sh
  cd android
  ./gradlew assembleDebug          # macOS and Linux
  gradlew.bat assembleDebug        # Windows
  ```

Checks run by CI: `./gradlew ktlintCheck lintDebug testDebugUnitTest assembleDebug`.

## Quick start (backend)

Requirements: JDK 21 and Docker. Details in [`backend/README.md`](backend/README.md).

```sh
cd infra && cp .env.example .env && docker compose -f docker-compose.dev.yml up -d db
cd ../backend && ./gradlew bootRun --args="--spring.profiles.active=local"   # gradlew.bat on Windows
```

### Firebase (optional)

Crashlytics and Analytics need `google-services.json` from the Firebase console. Drop it into
`android/app/`. The build works without it (Firebase is then simply not configured).

### Release signing

Copy `android/keystore.properties.example` to `android/keystore.properties` and fill in your
values, or provide the equivalent `MMR_*` environment variables (names listed in the example
file). Never commit `keystore.properties` or keystore files.

## Documentation

Read [`docs/AGENTS.md`](docs/AGENTS.md) first (it applies to humans and AI assistants alike), then:
[`PROJECT_OVERVIEW`](docs/PROJECT_OVERVIEW.md),
[`PRD`](docs/PRD.md),
[`ARCHITECTURE`](docs/ARCHITECTURE.md),
[`TASKS`](docs/TASKS.md),
[`PROGRESS`](docs/PROGRESS.md),
[`CODING_STANDARDS`](docs/CODING_STANDARDS.md),
[`TESTING_STRATEGY`](docs/TESTING_STRATEGY.md),
[`SECURITY`](docs/SECURITY.md),
[`DEPLOYMENT`](docs/DEPLOYMENT.md),
[`DECISIONS`](docs/DECISIONS.md).

## Contributing

Trunk-based development with short-lived branches, Conventional Commits and pull requests (see
`docs/CODING_STANDARDS.md` section 2). Do not commit secrets, real recorded traces or local config.

## License

TBD (proprietary until decided).

## Connecting the app to a local backend

Debug builds call `http://localhost:8080` by default. On a phone connected over USB, forward the port so the
phone's `localhost` reaches your computer:

```
adb reverse tcp:8080 tcp:8080
```

The emulator can use `http://10.0.2.2:8080` instead: put `mmr.apiBaseUrl=http://10.0.2.2:8080` in
`android/local.properties`. Plain HTTP is allowed only for `localhost`, `127.0.0.1` and `10.0.2.2`, and only in debug
builds. Release builds are HTTPS only. Start the backend with the `local` profile (see `backend/README.md`), so any
debug build can register without Play Integrity.
