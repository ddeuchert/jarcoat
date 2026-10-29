# JarCoat — Pre-Spec

> Wrap an executable JAR and run Java agents alongside it.

**Status:** pre-spec, v0.1 (2026-10-29). Distilled from the initial design conversation and a first
walking skeleton. Nothing here is final; section 11 lists what still needs a decision.

**Evidence tags** used throughout, so you know how much weight a statement carries:

| Tag | Meaning |
|---|---|
| **[R]** | Researched: backed by a source listed in `docs/RESEARCH.md` |
| **[V]** | Verified: observed by running the skeleton in this repo (JDK 21, Linux) |
| **[H]** | Hypothesis: reasoned but not yet tested |
| **[D]** | Decision taken in the design conversation |
| **[Q]** | Open question (see section 11) |

---

## 1. Problem

Java agents (`-javaagent`) must normally be passed on the JVM command line. People who ship an
executable JAR (a plain jar, a shaded/"jar-with-dependencies" jar, or a Spring Boot fat jar) often
want agents to start automatically with `java -jar app.jar`, without editing launch scripts or
container entrypoints.

The JDK offers `Launcher-Agent-Class` (JDK 9+) for one embedded agent. **[R]** Its limits: only one
agent, and the agent's argument string is always empty. **[R]** An agent jar also cannot simply be
nested inside the application jar; it must be unpacked.

## 2. Goals and non-goals

**Goals**

- G1. Take an executable JAR plus one or more agent JARs and produce a new executable JAR that starts the
  agents, in order, with per-agent option strings, before the application's `main`. **[D]**
- G2. Leave the original application JAR **byte-for-byte unmodified** inside the wrapper (nested,
  not merged). **[D]**
- G3. **Parity with `-javaagent`** (section 5.1): if an app works with command-line agents it works
  wrapped, and vice versa. **[D]**
- G4. Build-tool-agnostic core with thin adapters: Maven plugin, Gradle plugin, CLI. **[D]**
- G5. Reproducible output (same inputs give identical bytes). **[V]**

**Non-goals (v1)**

- N1. Java 8 support. **[D]** (User decision: not supported.)
- N2. Native/JVMTI agents (`-agentpath`, `-agentlib`). They cannot be started from inside a running JVM. **[R]**
- N3. Passing extra JVM flags (`-Xmx`, `-XX:*`, `--add-opens` beyond what the manifest allows).
- N4. Dynamic attach to running JVMs. **[D]** Self-attach is disabled by default since JDK 9, and JDK 21
  warns on dynamic loading (JEP 451). **[R]**
- N5. Hiding or obfuscating the agents. JarCoat is transparent by design.

## 3. Prior art (summary)

Full detail and sources in `docs/RESEARCH.md`.

- **agent-embedder-maven-plugin** (dev.aspectj) is the closest existing tool. **[R]** Maven only, LGPL-2.1,
  last release 2024-03-18. It embeds agents via a launcher agent that fans out to several agents, with option
  strings. It **unpacks** agent classes into the main jar rather than nesting the app.
- I found no Gradle plugin that embeds agents into the jar. **[R]** (single-search finding; re-verify) Existing
  Gradle plugins add a `-javaagent` flag to test tasks, start scripts or container entrypoints.

**How JarCoat differs:** nest the *unmodified* inner jar; classloader semantics defined by the parity rule; one
core shared by several build tools. If those differences don't hold up in practice, contributing Gradle support
to agent-embedder is the cheaper alternative. **[Q12]**

**Licensing note:** agent-embedder is LGPL. JarCoat's code was written independently; keep it that way (do not
copy from that project) (license decision Q4 is made: Apache-2.0, so the LGPL constraint still applies).

## 4. Architecture

### 4.1 Modules

| Module | Role | Dependencies |
|---|---|---|
| `jarcoat-launcher` | Tiny runtime copied into every wrapped jar | **none, ever** |
| `jarcoat-core` | Builds the outer jar | launcher |
| `jarcoat-cli` | `java -jar jarcoat-cli.jar ...` (shaded) | core |
| `jarcoat-maven-plugin` | `jarcoat:wrap` goal | core, Maven APIs |
| `jarcoat-gradle-plugin` | **not yet created** (M4) | core |

### 4.2 Outer jar layout **[V]**

```
outer.jar
├── META-INF/MANIFEST.MF                 Main-Class, Launcher-Agent-Class, Can-* flags, Add-Opens/Exports
├── META-INF/jarcoat/config.properties   inner main class, agent list + options, failure policy
├── META-INF/jarcoat/inner.jar           the unmodified application jar
├── META-INF/jarcoat/agents/0.jar ...    embedded agents, in start order
└── org/jarcoat/launcher/*.class          launcher runtime
```

### 4.3 Runtime sequence (mode `inprocess`) **[V]**

1. `java -jar outer.jar args...`. The JVM sees `Launcher-Agent-Class` and calls
   `LauncherAgent.agentmain` *before* `Main-Class`.
2. `LauncherAgent` extracts `inner.jar` to a temp directory and appends it to the **system class path**.
3. For each agent in order: extract it, append it to the system class path, load its `Premain-Class`
   via the system class loader, call `premain(options, inst)` (or the one-argument form).
4. `LauncherAgent` sets `started = true`.
5. `Bootstrap.main(args)` (the outer `Main-Class`) refuses to run if `started` is false (exit code 2). Otherwise
   it loads the inner `Main-Class` through the system class loader and invokes `main(args)`. The application's
   own exceptions and `System.exit` codes propagate unchanged.

## 5. Behavior contract

### 5.1 The parity rule **[D]**

> `java -jar coated.jar a b` must be observably identical to
> `java -javaagent:a1.jar=o1 -javaagent:a2.jar -jar inner.jar a b`.

Consequences:

- **Class-path order.** With real `-javaagent`, agent jars are appended *after* the application. **[R]** So the
  launcher appends the inner jar first, then agents. If both contain the same library, the application's copy
  wins, exactly as it would without JarCoat. Duplicate-library conflicts between app and agent are therefore
  **not a new problem** introduced by JarCoat, only the existing one. **[D]**
- **Loader identity.** The inner app runs in the system class loader. **[V]** (`Inner.class.getClassLoader() ==
  ClassLoader.getSystemClassLoader()` matches the reference run.)
- **Option strings.** A bare agent receives `null` options, like `-javaagent:x.jar`. **[V]**
- **Start order.** Agents start in configuration order, before the app's `main`. **[V]**
- **Observed differences** (documented, accepted): `java.class.path` shows only the outer jar. **[V]**
  Jar-relative lookups (e.g. via `CodeSource`) resolve to a temp directory. **[H]**

A parity test suite is the definition of "correct" (section 9).

### 5.2 Manifest handling **[D]**

- `Main-Class` → `org.jarcoat.launcher.Bootstrap`; `Launcher-Agent-Class` → `org.jarcoat.launcher.LauncherAgent`.
- `Can-Redefine-Classes`, `Can-Retransform-Classes`, `Can-Set-Native-Method-Prefix` are declared on the *outer*
  manifest as the **union** across embedded agents, because the JVM fixes capabilities per agent jar manifest. **[H]**
  (flag mapping is implemented; behavioral effect on retransform not yet tested)
- `Add-Opens`, `Add-Exports`, `Enable-Native-Access` are copied from the inner manifest up to the outer one, since
  the JVM only honors them from the main jar's manifest. **[R]** (spec behavior) **[H]** (not yet tested)
- `Boot-Class-Path` in an agent manifest: **not yet supported**; build warns, launcher warns. (M2)
- Build **fails** if an agent has no `Premain-Class`, or the inner jar has no `Main-Class` and none is configured. **[V]**

### 5.3 Failure policy **[D]**

`failOnAgentError` (default **true**): an agent that throws during startup aborts the JVM (fail-closed); with `false`
it is logged to stderr and skipped. **[V]** Both paths exercised. Note: fail-closed currently surfaces as a raw
`InvocationTargetException` stack trace; improving that message is a polish task.

### 5.4 Extraction **[V/H]**

Inner and agent jars are extracted to a fresh temp directory per launch and deleted on exit (best effort). **[V]**
Known weaknesses **[H]**: read-only or no-temp container filesystems, startup cost for large inner jars, and leftover
files after `kill -9`. See Q7 (content-hash cache with a configurable directory).

## 6. Build-tool integration

### 6.1 Maven (implemented, unbuilt, see README status)

```xml
<plugin>
  <groupId>org.jarcoat</groupId>           <!-- requires owning jarcoat.org, see D9 -->
  <artifactId>jarcoat-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions><execution>
    <goals><goal>wrap</goal></goals>
    <configuration>
      <agents>
        <agent>
          <groupId>com.example</groupId><artifactId>my-agent</artifactId>
          <options>key=value</options>
        </agent>
      </agents>
    </configuration>
  </execution></executions>
</plugin>
```

- Agents are normal dependencies, ideally `provided` scope so they are not packaged into the app. Resolved from
  `project.getArtifacts()`, or give `<file>` instead.
- Output is attached with classifier `jarcoat` (configurable). Runs in `package`, so plugin order relative to
  `jar`/`shade`/Spring Boot `repackage` matters (Q11).
- Naming follows the `<name>-maven-plugin` convention (the `maven-<name>-plugin` form is reserved for Apache). **[R]**

### 6.2 CLI (implemented) **[V]**

```
java -jar jarcoat-cli.jar --inner app.jar --out app-jarcoat.jar \
     --agent agents/a.jar=opt1=v1 --agent agents/b.jar [--main-class com.Main] [--no-fail]
```

The CLI is also the integration path for sbt, Bazel, Ant and anything else.

### 6.3 Gradle (not started) **[Q8]**

Plan: a plugin exposing a `jarcoat` extension and a `jarcoatWrap` task over the same core. Decide the plugin ID,
whether agents come from a dedicated configuration (e.g. `jarcoatAgent`), and how to wire to `jar`/`shadowJar`/
`bootJar`.

## 7. Verified results (what the skeleton has actually shown)

All on OpenJDK 21.0.x, Linux, real JVM processes, compiled with `--release 11`.

| Check | Result |
|---|---|
| Embedded launch matches `-javaagent` flags: two agents, options on one, none on the other, a transformer that sees the app's main class, system class loader identity, stdout compared | **Pass** |
| `java -cp outer.jar org.jarcoat.launcher.Bootstrap` (bypassing the manifest agent) | Exits 2 with a clear message |
| Inner `System.exit(3)` | Propagates, exit code 3 |
| Inner throws an exception | Propagates as a normal uncaught exception, exit 1 |
| Failing agent, default | JVM aborts (exit 1) |
| Failing agent, `failOnAgentError=false` | Logged, app runs, exit 0 |
| Same inputs, built twice | Byte-identical output |
| Build fails without inner `Main-Class` | Clear error |

## 8. Not yet verified (do these before trusting the design)

- **Maven build of this repo.** The POMs were never run: Maven Central was unreachable in the authoring sandbox.
  Java sources were compiled and tested with `javac` instead. Expect small POM fixes on the first `mvn verify`.
- **Maven plugin end-to-end** via `examples/` (`demo-app` + `demo-agent`).
- **Spring Boot / nested-jar apps.** The system-classloader approach is unproven with Boot's launcher and
  classloader. An old Spring Boot issue reports its launcher excluding agent jars from its class path. **[R]** Test
  on current Boot first; it is the biggest design risk. **[Q3]**
- **Real-world agents** (OpenTelemetry, Byte Buddy-based, JaCoCo, AspectJ weaver): behavior with retransformation,
  bootstrap injection and `Boot-Class-Path`.
- **JDK 11 and 17 at runtime.** Only JDK 21 was available; code is compiled with `--release 11`.
- **`Class-Path` manifest entries** inside the inner jar, multi-release inner jars, modular inner jars.
- **Windows** (temp paths, file locking of extracted jars).

## 9. Test strategy

1. **Parity tests (core):** run every case twice, wrapped and with real `-javaagent`, and diff stdout/exit code.
   (`ParityTest` is the seed.)
2. **Fixture matrix:** plain jar; shaded fat jar; Spring Boot jar; app with `ServiceLoader`; app reading its own
   jar via `CodeSource`; modular/multi-release jar; inner jar with `Class-Path`.
3. **Real agents:** at least one of OpenTelemetry, JaCoCo, and an AspectJ weaver, as a smoke test.
4. **JDK matrix in CI:** 11, 17, 21, latest.
5. **Maven IT** (maven-invoker-plugin) driving `examples/`.

## 10. Limitations and risks

- **Launch method.** `Launcher-Agent-Class` is honored only for `java -jar`. **[R]** Wrapped jars launched via
  `-cp` or an IDE skip the agents; `Bootstrap` detects this and exits rather than silently running unwrapped. **[V]**
- **Fail-hard startup.** An exception in an agent aborts the JVM. **[R]** `failOnAgentError=false` mitigates.
- **Shared capabilities.** One `Instrumentation` and one capability set; the union may grant more than a given
  agent asked for. **[H]**
- **Security.** JarCoat injects code into executables. Per the JDK docs the party deploying agents is responsible
  for verifying them. **[R]** Plan checksum pinning, provenance output (list embedded agents with SHA-256), and a
  `--list` command.
- **Signed jars.** Modifying contents invalidates signatures; sign the outer jar instead (Q9).
- **Name overlap with the sibling tool.** Be explicit in docs that this is not agent-embedder.

## 11. Decisions log and open questions

### Decisions

| # | Decision |
|---|---|
| D1 | Name: **JarCoat** (see `docs/RESEARCH.md` for candidates rejected and why) |
| D2 | Nest the unmodified inner jar; don't merge or unpack it |
| D3 | JDK 9+ only; default mode `inprocess` via `Launcher-Agent-Class` |
| D4 | Parity with `-javaagent` is the correctness criterion, including class-path order (app first, then agents) |
| D5 | Core is build-tool-agnostic; Maven, Gradle and CLI are thin adapters |
| D6 | Agents' manifest capabilities are unioned onto the outer manifest |
| D7 | Fail-closed on agent startup errors by default |
| D8 | Launcher module has zero dependencies |
| D9 | GroupId and Java packages are `org.jarcoat`. Domain `jarcoat.org` to be registered (cheaper than `.io`, and `.io` is a country-code domain whose future is uncertain). Verified on Maven Central with a DNS TXT record. Chosen over `io.github.<user>` for independence from a GitHub username. Decide before the first release: changing the groupId later changes the coordinates and consumers must update. If the domain were ever allowed to lapse, the fallback is to republish under a new groupId (a breaking change for consumers). |

### Open questions

| # | Question | Notes |
|---|---|---|
| Q1 | **Runtime JDK floor**: 11 or 17? | Launcher compiles to 11 now. Build-tool floor is separate (follows Maven/Gradle requirements). |
| Q2 | ~~GroupId~~ | **Resolved:** see D9. |
| Q3 | **Nested-jar inner apps (Spring Boot)** | Test system-loader approach; fallbacks: run Boot's `JarLauncher` explicitly, or fork mode. |
| Q4 | **License** | **Decided: Apache-2.0** (`LICENSE`, `<licenses>` in the parent POM, copyright header on every source file). |
| Q5 | **Extra modes**: `fork` (child JVM with true `-javaagent` semantics, but two PIDs) and `exec` (JDK 22+ FFM `execv`, one PID, POSIX only) | Defer; `inprocess` first. |
| Q6 | **Per-agent classloader isolation** (opt-in) | Would solve a problem `-javaagent` itself has; breaks agents that assume the system loader. |
| Q7 | **Extraction strategy** | Content-hash cache, configurable dir, read-only FS fallback. |
| Q8 | **Gradle plugin shape** | ID, configuration name, wiring to `jar`/`shadowJar`/`bootJar`. |
| Q9 | **Signing** | Sign outer jar; define behavior if the inner jar is signed. |
| Q10 | **`Boot-Class-Path` support** | Needs extraction paths + `appendToBootstrapClassLoaderSearch`. |
| Q11 | **Maven phase ordering** vs Spring Boot repackage/shade | Document or enforce. |
| Q12 | **Build new vs contribute to agent-embedder** | Revisit after Spring Boot results. |

## 12. Milestones

- **M0 (done in this repo):** walking skeleton: launcher, core builder, CLI, Maven mojo, parity tests, example. *Verify the
  Maven build.*
- **M1:** Maven IT via `examples/`; JDK 11/17/21 CI; Spring Boot fixture and decision on Q3.
- **M2:** `Boot-Class-Path`, nicer error messages, provenance report (`--list`, SHA-256), extraction cache (Q7).
- **M3:** optional `fork` fallback for `-cp`/native-agent cases; polish.
- **M4:** Gradle plugin.
- **M5:** docs site, Central + Plugin Portal publishing, 0.1.0 release. License is done (Q4).
  - **Set up Maven Central publishing when we reach the first release.** Verify the `org.jarcoat` namespace on the
    Central Portal (DNS TXT record on jarcoat.org, D9), generate a GPG signing key, add the publishing token and key as
    GitHub secrets, and add the `central-publishing-maven-plugin`, source/javadoc jars and signing to a release
    profile plus a release workflow. Model it on logaperture's `doc/specs/maven-central-publishing.md` and
    `.github/workflows/release.yml`. The POM already carries the required `url`, `licenses`, `developers` and `scm`.
    Spec it in `docs/specs/maven-central-publishing.md` before implementing.

## Appendix A: Alternatives considered

1. **Merge/unpack agents into the app jar** (agent-embedder's approach). Simple, but rewrites the app jar.
2. **Fork a child JVM** (`java -javaagent... -jar inner.jar`). Highest fidelity and the only way to pass extra JVM flags;
   costs: two JVMs and PIDs, signal and exit-code forwarding, a larger footprint, and any JDWP or agent flags on the
   original command line reach the outer JVM first (so the outer binds the debug port and the child can't). Kept as a
   possible fallback (Q5).
3. **`execv` via FFM (JDK 22+)** to replace the process in place. One PID on POSIX; none on Windows. **[H]** untested.
4. **Dynamic self-attach** (Attach API). Rejected: disabled by default since JDK 9, warned on in JDK 21. **[R]**
