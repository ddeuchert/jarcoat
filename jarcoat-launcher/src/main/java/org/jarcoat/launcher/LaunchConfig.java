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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Configuration baked into the outer jar at build time by jarcoat-core.
 *
 * <pre>
 * version=1
 * inner.resource=META-INF/jarcoat/inner.jar
 * inner.mainClass=com.example.Main
 * failOnAgentError=true
 * agent.count=2
 * agent.0.name=my-agent.jar
 * agent.0.resource=META-INF/jarcoat/agents/0.jar
 * agent.0.options=key=value
 * </pre>
 */
final class LaunchConfig {

    static final String RESOURCE = "META-INF/jarcoat/config.properties";

    static final class Agent {
        final String name;
        final String resource;
        final String options; // null when none

        Agent(String name, String resource, String options) {
            this.name = name;
            this.resource = resource;
            this.options = options;
        }
    }

    final String innerResource;
    final String innerMainClass;
    final boolean failOnAgentError;
    final List<Agent> agents;

    private LaunchConfig(String innerResource, String innerMainClass, boolean failOnAgentError, List<Agent> agents) {
        this.innerResource = innerResource;
        this.innerMainClass = innerMainClass;
        this.failOnAgentError = failOnAgentError;
        this.agents = Collections.unmodifiableList(agents);
    }

    static LaunchConfig load() throws IOException {
        Properties p = new Properties();
        try (InputStream in = LaunchConfig.class.getResourceAsStream("/" + RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing " + RESOURCE + " (is this a JarCoat-wrapped jar?)");
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        int count = Integer.parseInt(p.getProperty("agent.count", "0"));
        List<Agent> agents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String options = p.getProperty("agent." + i + ".options");
            if (options != null && options.isEmpty()) {
                options = null;
            }
            agents.add(new Agent(
                    p.getProperty("agent." + i + ".name", "agent-" + i),
                    required(p, "agent." + i + ".resource"),
                    options));
        }
        return new LaunchConfig(
                required(p, "inner.resource"),
                required(p, "inner.mainClass"),
                Boolean.parseBoolean(p.getProperty("failOnAgentError", "true")),
                agents);
    }

    private static String required(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null || v.isEmpty()) {
            throw new IOException("Missing '" + key + "' in " + RESOURCE);
        }
        return v;
    }
}
