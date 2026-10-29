/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jarcoat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The core correctness property of JarCoat (SPEC section 5):
 * <pre>java -jar coated.jar  ==  java -javaagent:a1=o1 -javaagent:a2 -jar inner.jar</pre>
 * These tests compile a tiny app and two tiny agents at test time, run both launch styles in
 * real JVMs, and compare stdout.
 */
class ParityTest {

    private static final String INNER_SRC = String.join("\n",
            "package demo;",
            "public class Inner {",
            "  public static void main(String[] args) {",
            "    System.out.println(\"inner.main args=\" + String.join(\",\", args));",
            "    System.out.println(\"inner.sawByAgent=\" + System.getProperty(\"agent.saw.inner\"));",
            "    System.out.println(\"inner.loaderIsSystem=\" + (Inner.class.getClassLoader() == ClassLoader.getSystemClassLoader()));",
            "  }",
            "}");

    private static final String AGENT1_SRC = String.join("\n",
            "package demo;",
            "import java.lang.instrument.*;",
            "import java.security.ProtectionDomain;",
            "public class Agent1 {",
            "  public static void premain(String opts, Instrumentation inst) {",
            "    System.out.println(\"agent1.premain opts=\" + opts);",
            "    inst.addTransformer(new ClassFileTransformer() {",
            "      public byte[] transform(ClassLoader l, String n, Class<?> c, ProtectionDomain p, byte[] b) {",
            "        if (\"demo/Inner\".equals(n)) {",
            "          System.out.println(\"agent1.saw \" + n);",
            "          System.setProperty(\"agent.saw.inner\", \"true\");",
            "        }",
            "        return null;",
            "      }",
            "    });",
            "  }",
            "}");

    private static final String AGENT2_SRC = String.join("\n",
            "package demo;",
            "import java.lang.instrument.Instrumentation;",
            "public class Agent2 {",
            "  public static void premain(String opts, Instrumentation inst) {",
            "    System.out.println(\"agent2.premain opts=\" + opts);",
            "  }",
            "}");

    @Test
    void embeddedMatchesJavaagentFlags(@TempDir Path tmp) throws Exception {
        Path inner = jar(tmp, "inner", "demo.Inner", INNER_SRC, attrs("Main-Class", "demo.Inner"));
        Path agent1 = jar(tmp, "agent1", "demo.Agent1", AGENT1_SRC,
                attrs("Premain-Class", "demo.Agent1", "Can-Retransform-Classes", "true"));
        Path agent2 = jar(tmp, "agent2", "demo.Agent2", AGENT2_SRC, attrs("Premain-Class", "demo.Agent2"));

        Path coated = tmp.resolve("coated.jar");
        JarCoatBuilder.build(new JarCoatSpec(inner, coated)
                .addAgent(new AgentSpec(agent1, "alpha=1,beta=2"))
                .addAgent(new AgentSpec(agent2, null)));

        Run reference = run("-javaagent:" + agent1 + "=alpha=1,beta=2", "-javaagent:" + agent2,
                "-jar", inner.toString(), "x", "y");
        Run embedded = run("-jar", coated.toString(), "x", "y");

        assertEquals(0, reference.exit, reference.stderr);
        assertEquals(0, embedded.exit, embedded.stderr);
        assertTrue(reference.stdout.contains("agent1.saw demo/Inner"), "reference run sanity: " + reference.stdout);
        assertEquals(reference.stdout, embedded.stdout,
                "embedded launch must be observably identical to -javaagent launch");
    }

    @Test
    void bootstrapRefusesToRunWithoutLauncherAgent(@TempDir Path tmp) throws Exception {
        Path inner = jar(tmp, "inner", "demo.Inner", INNER_SRC, attrs("Main-Class", "demo.Inner"));
        Path agent2 = jar(tmp, "agent2", "demo.Agent2", AGENT2_SRC, attrs("Premain-Class", "demo.Agent2"));
        Path coated = tmp.resolve("coated.jar");
        JarCoatBuilder.build(new JarCoatSpec(inner, coated).addAgent(new AgentSpec(agent2, null)));

        // -cp bypasses the Launcher-Agent-Class manifest attribute.
        Run r = run("-cp", coated.toString(), "org.jarcoat.launcher.Bootstrap");
        assertEquals(2, r.exit);
        assertTrue(r.stderr.contains("launcher agent did not run"), r.stderr);
    }

    @Test
    void buildFailsWhenInnerHasNoMainClass(@TempDir Path tmp) throws Exception {
        Path inner = jar(tmp, "inner", "demo.Inner", INNER_SRC, attrs());
        Path agent2 = jar(tmp, "agent2", "demo.Agent2", AGENT2_SRC, attrs("Premain-Class", "demo.Agent2"));
        JarCoatSpec spec = new JarCoatSpec(inner, tmp.resolve("out.jar")).addAgent(new AgentSpec(agent2, null));
        IOException e = assertThrows(IOException.class, () -> JarCoatBuilder.build(spec));
        assertTrue(e.getMessage().contains("Main-Class"), e.getMessage());
    }

    @Test
    void outputIsReproducible(@TempDir Path tmp) throws Exception {
        Path inner = jar(tmp, "inner", "demo.Inner", INNER_SRC, attrs("Main-Class", "demo.Inner"));
        Path agent2 = jar(tmp, "agent2", "demo.Agent2", AGENT2_SRC, attrs("Premain-Class", "demo.Agent2"));
        Path a = tmp.resolve("a.jar");
        Path b = tmp.resolve("b.jar");
        JarCoatBuilder.build(new JarCoatSpec(inner, a).addAgent(new AgentSpec(agent2, "k=v")));
        JarCoatBuilder.build(new JarCoatSpec(inner, b).addAgent(new AgentSpec(agent2, "k=v")));
        assertEquals(Arrays.toString(Files.readAllBytes(a)), Arrays.toString(Files.readAllBytes(b)));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Compile one source file and package it (with all generated classes) into a jar. */
    private static Path jar(Path dir, String name, String fqcn, String source, Map<String, String> manifestAttrs)
            throws Exception {
        Path src = dir.resolve(name + "-src").resolve(fqcn.replace('.', '/') + ".java");
        Path classes = dir.resolve(name + "-classes");
        Files.createDirectories(src.getParent());
        Files.createDirectories(classes);
        Files.write(src, source.getBytes(StandardCharsets.UTF_8));

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", classes.toString(), "--release", "11", src.toString());
        assertEquals(0, rc, "test source failed to compile: " + name);

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifestAttrs.forEach(mf.getMainAttributes()::putValue);

        Path jar = dir.resolve(name + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                jos.putNextEntry(new ZipEntry(classes.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, jos);
                jos.closeEntry();
            }
        }
        return jar;
    }

    private static final class Run {
        int exit;
        String stdout;
        String stderr;
    }

    /** Run a real JVM. stderr is kept separate: this environment injects JAVA_TOOL_OPTIONS noise there. */
    private static Run run(String... javaArgs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(Arrays.asList(javaArgs));
        Process p = new ProcessBuilder(cmd).start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread t = new Thread(() -> {
            try (InputStream in = p.getErrorStream()) {
                in.transferTo(err);
            } catch (IOException ignored) {
                // test helper
            }
        });
        t.start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "JVM timed out");
        t.join();
        Run r = new Run();
        r.exit = p.exitValue();
        r.stdout = new String(out, StandardCharsets.UTF_8).replace("\r\n", "\n");
        r.stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }
}
