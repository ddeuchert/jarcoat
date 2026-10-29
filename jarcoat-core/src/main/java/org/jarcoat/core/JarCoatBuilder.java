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

import org.jarcoat.launcher.LauncherAgent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Builds the outer ("coated") jar.
 *
 * <pre>
 * outer.jar
 * ├── META-INF/MANIFEST.MF            Main-Class, Launcher-Agent-Class, capabilities, Add-Opens/Exports
 * ├── META-INF/jarcoat/config.properties
 * ├── META-INF/jarcoat/inner.jar      the unmodified application jar
 * ├── META-INF/jarcoat/agents/N.jar   embedded agents, in start order
 * └── org/jarcoat/launcher/*.class     the launcher runtime
 * </pre>
 */
public final class JarCoatBuilder {

    static final String LAUNCHER_PACKAGE_PATH = "org/jarcoat/launcher/";
    static final String BOOTSTRAP_CLASS = "org.jarcoat.launcher.Bootstrap";
    static final String LAUNCHER_AGENT_CLASS = "org.jarcoat.launcher.LauncherAgent";
    static final String CONFIG_PATH = "META-INF/jarcoat/config.properties";
    static final String INNER_PATH = "META-INF/jarcoat/inner.jar";
    static final String AGENTS_DIR = "META-INF/jarcoat/agents/";

    /** Fixed entry timestamp (1980-01-02 UTC) so output is reproducible. */
    private static final long ENTRY_TIME = 315_619_200_000L;

    private JarCoatBuilder() {
    }

    /** @return non-fatal warnings to show the user */
    public static List<String> build(JarCoatSpec spec) throws IOException {
        List<String> warnings = new ArrayList<>();

        Attributes innerAttrs = readMainAttributes(spec.innerJar());
        String mainClass = spec.mainClass() != null ? spec.mainClass() : innerAttrs.getValue("Main-Class");
        if (mainClass == null || mainClass.trim().isEmpty()) {
            throw new IOException("Inner jar " + spec.innerJar() + " has no Main-Class; set mainClass explicitly");
        }

        Manifest manifest = new Manifest();
        Attributes out = manifest.getMainAttributes();
        out.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        out.putValue("Main-Class", BOOTSTRAP_CLASS);
        out.putValue("Launcher-Agent-Class", LAUNCHER_AGENT_CLASS);
        out.putValue("Created-By", "JarCoat");

        // Add-Opens / Add-Exports are only honored from the *main* jar's manifest: copy them up.
        for (String key : new String[] {"Add-Opens", "Add-Exports", "Enable-Native-Access"}) {
            String v = innerAttrs.getValue(key);
            if (v != null) {
                out.putValue(key, v);
            }
        }

        // Instrumentation capabilities are fixed per agent *jar manifest*; we declare the union.
        boolean redefine = false;
        boolean retransform = false;
        boolean nativePrefix = false;
        for (AgentSpec agent : spec.agents()) {
            Attributes a = readMainAttributes(agent.jar());
            if (a.getValue("Premain-Class") == null) {
                throw new IOException("Agent " + agent.jar() + " has no Premain-Class in its manifest");
            }
            redefine |= isTrue(a.getValue("Can-Redefine-Classes"));
            retransform |= isTrue(a.getValue("Can-Retransform-Classes"));
            nativePrefix |= isTrue(a.getValue("Can-Set-Native-Method-Prefix"));
            if (a.getValue("Boot-Class-Path") != null) {
                warnings.add("Agent " + agent.name() + " declares Boot-Class-Path, which is not yet supported");
            }
        }
        if (redefine) {
            out.putValue("Can-Redefine-Classes", "true");
        }
        if (retransform) {
            out.putValue("Can-Retransform-Classes", "true");
        }
        if (nativePrefix) {
            out.putValue("Can-Set-Native-Method-Prefix", "true");
        }

        Path target = spec.outputJar().toAbsolutePath();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path tmp = Files.createTempFile(target.getParent(), "jarcoat-", ".tmp");
        try {
            try (OutputStream raw = Files.newOutputStream(tmp);
                 JarOutputStream jos = new JarOutputStream(raw, manifest)) {
                copyLauncherClasses(jos);
                putBytes(jos, CONFIG_PATH, config(spec, mainClass).getBytes(StandardCharsets.UTF_8));
                putFile(jos, INNER_PATH, spec.innerJar());
                int i = 0;
                for (AgentSpec agent : spec.agents()) {
                    putFile(jos, AGENTS_DIR + i + ".jar", agent.jar());
                    i++;
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return warnings;
    }

    // ---- config ---------------------------------------------------------------------------------

    private static String config(JarCoatSpec spec, String mainClass) {
        StringBuilder sb = new StringBuilder();
        sb.append("version=1\n");
        sb.append("inner.resource=").append(esc(INNER_PATH)).append('\n');
        sb.append("inner.mainClass=").append(esc(mainClass.trim())).append('\n');
        sb.append("failOnAgentError=").append(spec.failOnAgentError()).append('\n');
        sb.append("agent.count=").append(spec.agents().size()).append('\n');
        int i = 0;
        for (AgentSpec agent : spec.agents()) {
            sb.append("agent.").append(i).append(".name=").append(esc(agent.name())).append('\n');
            sb.append("agent.").append(i).append(".resource=").append(esc(AGENTS_DIR + i + ".jar")).append('\n');
            if (agent.options() != null) {
                sb.append("agent.").append(i).append(".options=").append(esc(agent.options())).append('\n');
            }
            i++;
        }
        return sb.toString();
    }

    /** Escape a value for java.util.Properties. */
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '=': case ':': case '#': case '!':
                    sb.append('\\').append(c);
                    break;
                case ' ':
                    sb.append(i == 0 ? "\\ " : " ");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- launcher classes -------------------------------------------------------------------------

    /** Copies org/jarcoat/launcher/** from wherever the launcher module is on our class path. */
    private static void copyLauncherClasses(JarOutputStream jos) throws IOException {
        Path source;
        try {
            source = Paths.get(LauncherAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IOException("Cannot locate the jarcoat-launcher classes", e);
        }
        boolean any = false;
        if (Files.isDirectory(source)) {
            Path root = source.resolve(LAUNCHER_PACKAGE_PATH);
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = new ArrayList<>();
                walk.filter(Files::isRegularFile).sorted().forEach(files::add);
            }
            for (Path f : files) {
                String name = source.relativize(f).toString().replace('\\', '/');
                putFile(jos, name, f);
                any = true;
            }
        } else {
            try (JarFile jf = new JarFile(source.toFile())) {
                List<JarEntry> entries = new ArrayList<>();
                for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                    JarEntry je = e.nextElement();
                    if (!je.isDirectory() && je.getName().startsWith(LAUNCHER_PACKAGE_PATH)) {
                        entries.add(je);
                    }
                }
                entries.sort((a, b) -> a.getName().compareTo(b.getName()));
                for (JarEntry je : entries) {
                    ZipEntry ze = new ZipEntry(je.getName());
                    ze.setTime(ENTRY_TIME);
                    jos.putNextEntry(ze);
                    try (java.io.InputStream in = jf.getInputStream(je)) {
                        in.transferTo(jos);
                    }
                    jos.closeEntry();
                    any = true;
                }
            }
        }
        if (!any) {
            throw new IOException("No launcher classes found under " + source);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static Attributes readMainAttributes(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            return mf == null ? new Attributes() : mf.getMainAttributes();
        }
    }

    private static boolean isTrue(String v) {
        return v != null && Boolean.parseBoolean(v.trim());
    }

    private static void putBytes(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        ze.setTime(ENTRY_TIME);
        jos.putNextEntry(ze);
        jos.write(bytes);
        jos.closeEntry();
    }

    private static void putFile(JarOutputStream jos, String name, Path file) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        ze.setTime(ENTRY_TIME);
        jos.putNextEntry(ze);
        Files.copy(file, jos);
        jos.closeEntry();
    }
}
