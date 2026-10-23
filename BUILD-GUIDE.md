# Ping CRM on Grails Build Guide

This guide explains how to build **Ping CRM on Grails** (a Grails + Vue.js + Inertia.js demo CRM) on any machine: Linux, macOS (Intel or Apple Silicon), or Windows, on x86_64 or ARM64.

All paths in this guide are relative to the project root (the folder that contains `build.gradle`) unless stated otherwise. Replace placeholders such as `<GRADLE_INSTALL_DIR>` with locations of your choice.

---

## 1. What you are building

| Item | Value |
|------|-------|
| Back end | **Apache Grails 7.0.4** (Spring Boot 3, Groovy 4), Spring Security, Hibernate 5 |
| Front end | Vue 3 + Inertia.js, bundled with **Vite** (sources in `src/main/javascript/`) |
| Build tool | Gradle **8.14.3** (`gradle/wrapper/gradle-wrapper.properties`) with a `buildSrc` |
| Required JDK | **17** (`javaVersion=17` in `gradle.properties`; `.sdkmanrc` pins a Liberica 17) |
| Node.js / npm | **Not required**: the Gradle Node plugin downloads Node.js 18.17.1 automatically during the build |
| Database | In-memory H2 (recreated on every start, sample data loaded at start-up) |
| Outputs | `build/libs/pingcrm-grails-<version>.war` (executable Spring Boot WAR) and `build/libs/pingcrm-grails-<version>-plain.war` |

No Grails CLI is needed; everything is driven by Gradle.

---

## 2. Things to know before building

### 2.1 The Gradle wrapper jar is missing

`gradle/wrapper/gradle-wrapper.jar` is not in the repository, so `./gradlew` and `gradlew.bat` fail (`Could not find or load main class org.gradle.wrapper.GradleWrapperMain`). Install **Gradle 8.14.3** and run `gradle` directly (section 4).

Optional: with Gradle installed, `gradle wrapper --gradle-version 8.14.3` regenerates the wrapper.

### 2.2 How the front end is built

`build.gradle` wires the front end into the normal Gradle build:

1. `nodeSetup` downloads Node.js (version 18.17.1, chosen by the `com.github.node-gradle.node` plugin) into `.gradle/nodejs/` for your OS and CPU.
2. `npmInstall` runs `npm install` with that Node, creating `node_modules/` (versions locked by `package-lock.json`).
3. `viteBuild` runs `npm run build`, which writes the client bundle to `src/main/resources/public/dist/` and the SSR bundle to `src/main/resources/ssr/`.
4. `processResources` depends on these tasks, so the bundles end up inside the WAR.

All generated folders (`node_modules/`, `src/main/resources/public/dist/`, `src/main/resources/ssr/`, `.gradle/`) are in `.gitignore`. You only need your own Node.js/npm if you want to use the Vite dev server (section 7.3).

### 2.3 The build must run inside a Git repository

The `com.gorylenko.gradle-git-properties` plugin generates `git.properties` (commit id, time, branch) and **fails when there is no `.git` folder**:

```
Execution failed for task ':generateGitProperties'.
> No Git repository found.
```

Skipping the task (`-x generateGitProperties`) is not a fix: the build then succeeds, but the application loads `classpath:git.properties` at start-up (`src/main/groovy/pingcrm/config/GitPropertiesLoader.groovy`) and fails to start without it.

- **Building from a `git clone`:** nothing to do.
- **Building from a source archive (ZIP/tarball without `.git`):** create a local repository with one commit before building (requires the `git` CLI, any OS):

  ```bash
  git init
  git add -A
  git -c user.name=build -c user.email=build@localhost commit -m "source snapshot"
  ```

The plugin reads the repository through JGit, so the `git` CLI itself is not needed for the build when `.git` already exists.

### 2.4 Dependency repositories

The build downloads from Maven Central, the Gradle Plugin Portal, `https://repo.grails.org/grails/restricted`, the Apache repositories (`https://repository.apache.org/content/repositories/staging` and `/snapshots`, for `org.apache.grails` / `org.apache.groovy` only), `https://nodejs.org/dist` (Node.js) and `https://registry.npmjs.org` (npm packages). All must be reachable from the build machine.

---

## 3. Install JDK 17

Any OpenJDK 17 build works (Eclipse Temurin, Azul Zulu, Amazon Corretto, BellSoft Liberica, Microsoft, etc.), with native x86_64 and ARM64 builds including Apple Silicon.

| Platform | Command |
|----------|---------|
| SDKMAN (Linux, macOS, WSL) | `sdk env install` in the project root (uses `.sdkmanrc`), or `sdk install java 17.<x>-tem` |
| Debian / Ubuntu | `sudo apt install openjdk-17-jdk` |
| Fedora / RHEL | `sudo dnf install java-17-openjdk-devel` |
| Arch | `sudo pacman -S jdk17-openjdk` |
| macOS (Homebrew) | `brew install --cask temurin@17` |
| Windows (winget) | `winget install EclipseAdoptium.Temurin.17.JDK` |
| Windows (Chocolatey) | `choco install temurin17` |
| Manual | https://adoptium.net/temurin/releases/?version=17 |

Verify:

```bash
java -version    # must print version "17.x" (or newer, see note)
```

Note: the README says "Java 17+". The code is compiled for release 17; building and running on JDK 21 is expected to work, but 17 is the tested version.

---

## 4. Install Gradle 8.14.3

| Option | Command |
|--------|---------|
| SDKMAN (Linux, macOS, WSL) | `sdk install gradle 8.14.3` |
| Manual (all platforms) | Download https://services.gradle.org/distributions/gradle-8.14.3-bin.zip and extract it to `<GRADLE_INSTALL_DIR>` |

Use 8.14.3 to match the project (package managers may install Gradle 9, which has not been verified with this build).

**Linux / macOS / WSL:**

```bash
export JAVA_HOME=<path-to-jdk17>
export PATH="$JAVA_HOME/bin:<GRADLE_INSTALL_DIR>/gradle-8.14.3/bin:$PATH"
```

**Windows PowerShell:**

```powershell
$env:JAVA_HOME = "<path-to-jdk17>"
$env:Path = "$env:JAVA_HOME\bin;<GRADLE_INSTALL_DIR>\gradle-8.14.3\bin;$env:Path"
```

**Windows cmd.exe:**

```bat
set JAVA_HOME=<path-to-jdk17>
set PATH=%JAVA_HOME%\bin;<GRADLE_INSTALL_DIR>\gradle-8.14.3\bin;%PATH%
```

Verify:

```bash
gradle -version    # Gradle 8.14.3, JVM: 17.x
```

---

## 5. Build

From the project root:

```bash
gradle --no-daemon assemble
```

The first build compiles `buildSrc`, downloads all Java dependencies (cached in `~/.gradle`, `%USERPROFILE%\.gradle` on Windows), downloads Node.js, runs `npm install` and the Vite build, and takes a few minutes. Outputs in `build/libs/`:

| File | Description |
|------|-------------|
| `pingcrm-grails-<version>.war` | Executable Spring Boot WAR with the bundled front end (about 105 MB). This is the artifact to run. |
| `pingcrm-grails-<version>-plain.war` | Plain WAR for an external Jakarta EE 10 servlet container (Tomcat 10.1+) |

Other useful commands:

```bash
gradle --no-daemon viteBuild    # front end only
gradle --no-daemon clean        # delete build/ (node_modules/ and the Vite output stay)
gradle --no-daemon check        # unit + integration tests (integration tests use Geb/Firefox)
```

The `generateGitProperties` task records Git commit information in the build and needs a `.git` folder (section 2.3).

---

## 6. Build with Docker (no local JDK, Gradle or Node.js)

Works on any OS and CPU that runs Docker (`eclipse-temurin:17-jdk` is multi-arch, and the Node plugin downloads the matching Node.js build). From the project root:

```bash
docker run --rm -v "$PWD":/src -w /src eclipse-temurin:17-jdk bash -c '
  set -e
  apt-get update -qq && apt-get install -y -qq unzip curl >/dev/null
  curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.14.3-bin.zip
  unzip -q /tmp/gradle.zip -d /opt
  /opt/gradle-8.14.3/bin/gradle --no-daemon assemble
'
```

Run it from a Git clone (the mounted folder must contain `.git`, section 2.3). On Windows PowerShell replace `"$PWD":/src` with `"${PWD}:/src"`. On Linux, files created by the container (`build/`, `.gradle/`, `node_modules/`, the Vite output) may be owned by `root`; remove them with `sudo` if needed.

Important: if you build alternately on the host and in a container (or on different operating systems) from the same folder, delete `node_modules/` and `.gradle/nodejs/` first. They contain platform-specific binaries (for example esbuild for Vite).

---

## 7. Run

### 7.1 Executable WAR

```bash
java -jar build/libs/pingcrm-grails-<version>.war
# Grails application running at http://localhost:8080 in environment: production
java -jar build/libs/pingcrm-grails-<version>.war --server.port=9090   # other port
```

Open http://localhost:8080 and log in with the seeded account:

| Email | Password |
|-------|----------|
| johndoe@example.com | secret |

The database is in memory: all changes are lost when the application stops, and fresh sample data is generated at every start.

### 7.2 From source

```bash
gradle --no-daemon bootRun                          # development environment
gradle --no-daemon -Dgrails.env=production bootRun  # production environment, bundled client files
```

### 7.3 Development with hot module replacement (needs Node.js >= 18 and npm)

```bash
npm install
npm run serve            # Vite dev server on port 3000
gradle --no-daemon bootRun   # in a second terminal
```

### 7.4 Server-side rendering (optional)

SSR is disabled by default (`inertia.ssr.enabled: false` in `grails-app/conf/application.yml`). Enabling it requires Node.js >= 18 on the machine that runs the application; the SSR bundle is built to `src/main/resources/ssr/ssr.mjs` by the normal build.

---

## 8. Troubleshooting

| Symptom | Cause / Fix |
|---------|-------------|
| `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` | Wrapper jar missing (section 2.1). Use Gradle 8.14.3 directly. |
| `invalid source release: 17` / `Unsupported class file major version` | Wrong JDK. Use JDK 17. |
| `nodeSetup` fails / cannot download `node-v18.17.1-...` | `https://nodejs.org/dist` blocked. Allow it, or configure a mirror with the Node plugin's `distBaseUrl` setting. |
| `npmInstall` fails with network errors | `https://registry.npmjs.org` blocked, or an npm proxy is needed (`npm config set proxy ...`, honoured by the downloaded npm). |
| `viteBuild` fails with `esbuild` / "installed for another platform" errors | `node_modules/` was created on another OS/CPU. Delete `node_modules/` and `.gradle/nodejs/` and rebuild (section 6). |
| `generateGitProperties` fails with "No Git repository found" | Building from an archive without `.git`. Build from a Git clone, or create a repository with one commit (section 2.3). |
| `Could not resolve org.apache.grails:...` | Apache / Grails repositories blocked (section 2.4). |
| Port 8080 already in use | `--server.port=<port>` for the WAR, `-Dserver.port=<port>` for `bootRun`. |

---

## 9. Quick reference

```bash
# Requirements: JDK 17 + Gradle 8.14.3 on PATH (Node.js is downloaded by the build)
java -version              # 17.x
gradle -version            # 8.14.3

# Build
gradle --no-daemon assemble        # -> build/libs/pingcrm-grails-<version>.war

# Run
java -jar build/libs/pingcrm-grails-<version>.war
# http://localhost:8080   login: johndoe@example.com / secret
```
