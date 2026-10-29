# JarCoat

> Wrap an executable JAR and run Java agents alongside it.

JarCoat takes an executable JAR plus one or more Java agent JARs and produces a new executable JAR.
`java -jar app-jarcoat.jar` then starts the agents (in order, with per-agent options) before your
application's `main`, as if you had passed `-javaagent` flags, with no change to your launch command and
your original JAR nested inside, untouched.

**Status: pre-alpha template.** The design lives in [`docs/SPEC.md`](docs/SPEC.md); the research behind it
in [`docs/RESEARCH.md`](docs/RESEARCH.md). Start with the spec, especially sections 7 (verified) and 8 (not yet verified).

## Layout

| Path | What |
|---|---|
| `jarcoat-launcher/` | Tiny dependency-free runtime copied into every wrapped jar (`LauncherAgent`, `Bootstrap`) |
| `jarcoat-core/` | Builds the wrapped jar; contains `ParityTest` (embedded launch vs real `-javaagent`) |
| `jarcoat-cli/` | `java -jar jarcoat-cli.jar --inner app.jar --out out.jar --agent a.jar=opts` |
| `jarcoat-maven-plugin/` | `jarcoat:wrap` goal |
| `examples/` | `demo-app` + `demo-agent` using the plugin (separate reactor) |
| `docs/` | Spec and research notes |

## Build

```bash
mvn verify                      # build + tests (needs JDK 11+; tested on 21)
mvn install                     # install locally so examples/ can use the plugin
mvn -f examples/pom.xml verify  # wrap the demo app
java -jar examples/demo-app/target/demo-app-1.0.0-jarcoat.jar one two
```

Expected output of the last command (derived from the code; not yet run):

```
[demo-agent] premain, options=greeting=hi
[demo-agent] saw demo/app/Hello being loaded
Hello from demo-app, args=one,two
```

## What has and hasn't been run

- **Verified:** the Java sources compile with `javac --release 11`, and the four `ParityTest` cases pass on JDK 21
  (embedded launch matches `-javaagent`; exit codes; failure policy; reproducible output). The CLI was exercised by hand.
- **Not yet run:** the **Maven build itself**. The template was authored in a sandbox that couldn't reach Maven Central,
  so the POMs, the Maven plugin and `examples/` have never been built. Expect to fix small POM issues on your first
  `mvn verify`.
- **Not yet tested:** Spring Boot jars, real-world agents, JDK 11/17, Windows. See `docs/SPEC.md` section 8.

## First things to do

1. Run `mvn verify`, fix whatever the POMs need, then `mvn install` and run the example.
2. Register `jarcoat.org`: the groupId `org.jarcoat` depends on it, and Maven Central verifies ownership with a DNS TXT
   record (spec D9). The license is settled (Apache-2.0, Q4).
3. Check the `jarcoat` name on GitHub, Maven Central, the Gradle Plugin Portal and a domain registrar (`docs/RESEARCH.md` section 4).
4. Add a Spring Boot fixture; it decides whether the in-process design holds for nested-jar apps (spec Q3).

## Known gaps

`Boot-Class-Path` in agent manifests, the extraction cache, `fork`/`exec` modes, and the Gradle plugin are specified
but not implemented. See the milestones in the spec.
