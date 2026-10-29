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
package org.jarcoat.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Entry point named by the outer jar's {@code Launcher-Agent-Class} manifest attribute.
 *
 * <p>The JVM calls {@link #agentmain} before the outer jar's {@code Main-Class}. We:
 * <ol>
 *   <li>extract the inner jar and append it to the system class path <em>first</em>
 *       (matches {@code -javaagent}, where agent jars are appended after the application),</li>
 *   <li>extract each embedded agent jar, append it to the system class path, and invoke its
 *       {@code premain} in the configured order with the configured options.</li>
 * </ol>
 *
 * <p>Design rule: behave like {@code java -javaagent:a.jar=opts -jar inner.jar}. See docs/SPEC.md.
 */
public final class LauncherAgent {

    /** Set once all agents are started; checked by {@link Bootstrap}. */
    static volatile boolean started;

    private LauncherAgent() {
    }

    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        LaunchConfig cfg = LaunchConfig.load();

        Path dir = Files.createTempDirectory("jarcoat-");
        dir.toFile().deleteOnExit(); // registered first => deleted last

        // 1. inner jar goes on the system class path first.
        Path inner = extract(cfg.innerResource, dir.resolve("inner.jar"));
        inst.appendToSystemClassLoaderSearch(new JarFile(inner.toFile()));

        // 2. agents, in configured order.
        int i = 0;
        for (LaunchConfig.Agent agent : cfg.agents) {
            try {
                startAgent(inst, dir, i, agent);
            } catch (Throwable t) {
                String msg = "JarCoat: failed to start agent '" + agent.name + "': " + t;
                if (cfg.failOnAgentError) {
                    throw new IllegalStateException(msg, t); // JVM aborts, by design (fail-closed)
                }
                System.err.println(msg + " (continuing: failOnAgentError=false)");
            }
            i++;
        }
        started = true;
    }

    private static void startAgent(Instrumentation inst, Path dir, int index, LaunchConfig.Agent agent)
            throws Exception {
        Path file = extract(agent.resource, dir.resolve("agent-" + index + ".jar"));

        String premainClass;
        try (JarFile jf = new JarFile(file.toFile())) {
            Manifest mf = jf.getManifest();
            Attributes attrs = mf == null ? new Attributes() : mf.getMainAttributes();
            premainClass = attrs.getValue("Premain-Class");
            if (premainClass == null) {
                throw new IllegalStateException("no Premain-Class in manifest");
            }
            if (attrs.getValue("Boot-Class-Path") != null) {
                // TODO(M2): honor Boot-Class-Path via inst.appendToBootstrapClassLoaderSearch
                System.err.println("JarCoat: warning: Boot-Class-Path in '" + agent.name + "' is not yet supported");
            }
        }

        inst.appendToSystemClassLoaderSearch(new JarFile(file.toFile()));

        Class<?> c = Class.forName(premainClass.trim(), true, ClassLoader.getSystemClassLoader());
        try {
            Method m = c.getMethod("premain", String.class, Instrumentation.class);
            m.invoke(null, agent.options, inst);
        } catch (NoSuchMethodException e) {
            Method m = c.getMethod("premain", String.class);
            m.invoke(null, agent.options);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private static Exception unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        return cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
    }

    private static Path extract(String resource, Path target) throws IOException {
        try (InputStream in = LauncherAgent.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IOException("Missing embedded resource: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        return target;
    }
}
