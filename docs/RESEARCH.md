# JarCoat — Research Notes

Companion to `SPEC.md`. Raw findings from the initial research session (2026-10-29), with sources, so
claims in the spec can be re-checked. Web search only; registries were not queryable (see section 4).

## 1. Prior art

### agent-embedder-maven-plugin (closest match)

- Repo: <https://github.com/dev-aspectj/agent-embedder-maven-plugin>
- What it does: embeds Java agents into an executable JAR so they start on JRE 9+, using a launcher agent that can start
  multiple agents and pass per-agent options.
- Maven only; LGPL-2.1; ~2 stars when checked; last release 2024-03-18
  (<https://badges.mvnrepository.com/artifact/dev.aspectj>).
- Mechanism: **unpacks** the agent and edits the main jar's manifest; the author's announcement lists why embedding as a
  plain nested jar doesn't work with the JDK mechanism alone:
  <https://eclipse.org/lists/aspectj-users/msg15585.html>
- Has an integration test embedding the AspectJ weaver and a custom agent into a Spring Boot jar.

### JDK mechanism

- `Launcher-Agent-Class` main-manifest attribute (JDK 9+): starts one agent's `agentmain` before the app's `main`; agent
  args are always the empty string; failure to start aborts the JVM:
  <https://cs-214.epfl.ch/jdk-21.0.9-docs/api/java.instrument/java/lang/instrument/package-summary.html>,
  <https://java-browser.yawk.at/java/11/java.instrument/java/lang/instrument/package-info.java>
- Origin of the attribute (Mark Reinhold proposal, 2017):
  <https://mail.openjdk.org/pipermail/jigsaw-dev/2017-April/012133.html>
- Self-attach disallowed by default since JDK 9; JEP 451 warns on dynamic agent loading:
  <https://openjdk.org/jeps/451>, <https://mail.openjdk.org/pipermail/jdk-dev/2023-May/007776.html>
- Command-line agent jars are appended to the system class path (basis for the parity ordering rule): see the
  `java.lang.instrument` package docs above.

### Other tools in the neighborhood (not competitors)

- Gradle plugins that attach `-javaagent` to test tasks, start scripts and Jib images:
  <https://github.com/punya/javaagent-gradle-plugin>, <https://plugins.gradle.org/plugin/io.github.neboskreb.javaagent>
- Credit Karma exec-jar plugin (jar-of-jars format with its own classloader, agent support):
  <https://github.com/creditkarma/maven-exec-jar-plugin>
- Spring Boot issue about the launcher classloader excluding agent jars (old; retest on current Boot):
  <https://github.com/spring-projects/spring-boot/issues/4868>

## 2. Design conclusions reached in conversation

- Passing through `main` of an outer jar and relaunching a child JVM is possible but costs a second JVM and PID.
- Using `Launcher-Agent-Class` in-process avoids that; JDK 8 dropped, so no fallback is required for it.
- Duplicate libraries between app and agent are already an issue with plain `-javaagent`; the wrapper should match its
  ordering (app first, agents appended after) rather than invent new behavior. A mid-conversation suggestion to append
  agents first was corrected: the real `-javaagent` ordering puts the application first.
- Shaded/"jar-with-dependencies" inner jars are the easy case; nested-jar formats (Spring Boot) are the stress test.

## 3. Naming research

| Candidate | Finding | Verdict |
|---|---|---|
| JAgent | Devexperts' GPLv3 agent framework (<https://github.com/devexperts/jagent>) and a Creatures-game toolkit on Launchpad | Rejected |
| JavaAgentWrapper | Accurate but long; "wrapper" evokes Tanuki Service Wrapper / Maven & Gradle wrappers | Rejected |
| Remora | RemoraJ is a Java bytecode agent (<https://github.com/Nastel/remoraj>); also a Gradle plugin and a Grails plugin | Rejected |
| Sidejar | No Java project, but an active software company (Sidejar LLC, sidejar.studio / sidejar.co) and a record label | Rejected |
| Shim | Free as a project name but extremely generic (`java10-shim`, `shims`, ...) | Rejected |
| JarWrap | Same-name Java tool (jarwrap.online); also Debian `jarwrapper`, Apache River `JarWrapper`, stbachmann/JarWrapper | Rejected |
| **JarCoat** | Exact-name and variant searches found no Java project; near neighbors: `jarjar` (different job), COAT (`coatjava`, unrelated) | **Chosen** |

Any name containing "agent" competes with AI-agent results in search; that was a deliberate reason to avoid it.

## 4. Availability checks still to do by hand

Registry search endpoints were blocked in the research session, so absence in web search is **not** proof of availability:

1. GitHub: user/org and repo name `jarcoat`
2. Maven Central: `org.jarcoat` namespace claimable once `jarcoat.org` is registered (SPEC D9); `jarcoat-*` artifactIds unused
3. Gradle Plugin Portal: plugin ID
4. Domain: `jarcoat.org` availability and its **renewal** price (registrars often advertise a low first-year price); and a USPTO trademark search

Domain choice rationale: `.io` is a country-code TLD for the British Indian Ocean Territory, whose sovereignty is being
transferred to Mauritius, so its long-term status is uncertain (Domain Incite, The Register); `.org` is a generic TLD with no
such question. See SPEC D9.

## 5. Authoring-environment notes

- JDK 21 and Maven 3.9.11 were present; Maven Central (`repo.maven.apache.org`, `repo1.maven.org`) was not on the
  sandbox's allowed domains, so `mvn` could not download plugins or dependencies. The Java sources were compiled with
  `javac`, and `ParityTest` was run with stand-ins for the JUnit annotations/assertions. The POMs themselves are
  therefore **unbuilt**.
