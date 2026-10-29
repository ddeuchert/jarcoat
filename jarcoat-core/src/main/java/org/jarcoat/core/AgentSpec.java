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
import java.util.Objects;

/** One agent jar to embed, plus the option string it would receive after '=' on -javaagent. */
public final class AgentSpec {

    private final Path jar;
    private final String options;

    public AgentSpec(Path jar, String options) {
        this.jar = Objects.requireNonNull(jar, "jar");
        this.options = options == null || options.isEmpty() ? null : options;
    }

    public Path jar() {
        return jar;
    }

    /** Option string, or {@code null} if none (same as the JVM passes for a bare -javaagent:x.jar). */
    public String options() {
        return options;
    }

    public String name() {
        return jar.getFileName().toString();
    }
}
