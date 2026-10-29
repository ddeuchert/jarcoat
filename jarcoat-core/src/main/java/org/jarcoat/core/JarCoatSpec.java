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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Everything needed to build one wrapped jar. */
public final class JarCoatSpec {

    private final Path innerJar;
    private final Path outputJar;
    private final List<AgentSpec> agents = new ArrayList<>();
    private String mainClass;
    private boolean failOnAgentError = true;

    public JarCoatSpec(Path innerJar, Path outputJar) {
        this.innerJar = Objects.requireNonNull(innerJar, "innerJar");
        this.outputJar = Objects.requireNonNull(outputJar, "outputJar");
    }

    public JarCoatSpec addAgent(AgentSpec agent) {
        agents.add(Objects.requireNonNull(agent, "agent"));
        return this;
    }

    /** Override the inner jar's Main-Class (needed when its manifest has none). */
    public JarCoatSpec mainClass(String mainClass) {
        this.mainClass = mainClass;
        return this;
    }

    /** If true (default) a failing agent aborts the JVM at startup; if false it is logged and skipped. */
    public JarCoatSpec failOnAgentError(boolean failOnAgentError) {
        this.failOnAgentError = failOnAgentError;
        return this;
    }

    public Path innerJar() {
        return innerJar;
    }

    public Path outputJar() {
        return outputJar;
    }

    public List<AgentSpec> agents() {
        return Collections.unmodifiableList(agents);
    }

    public String mainClass() {
        return mainClass;
    }

    public boolean failOnAgentError() {
        return failOnAgentError;
    }
}
