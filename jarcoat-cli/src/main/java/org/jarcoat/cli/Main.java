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
package org.jarcoat.cli;

import org.jarcoat.core.AgentSpec;
import org.jarcoat.core.JarCoatBuilder;
import org.jarcoat.core.JarCoatSpec;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Usage:
 * <pre>
 * java -jar jarcoat-cli.jar --inner app.jar --out app-coated.jar \
 *      --agent agents/otel.jar=service.name=demo --agent agents/other.jar [--main-class com.Main] [--no-fail]
 * </pre>
 * The text after the first '=' in an --agent value is the option string (like -javaagent:path=options).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path inner = null;
        Path out = null;
        String mainClass = null;
        boolean fail = true;
        java.util.List<String> agents = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--inner": inner = Paths.get(next(args, ++i)); break;
                case "--out": out = Paths.get(next(args, ++i)); break;
                case "--agent": agents.add(next(args, ++i)); break;
                case "--main-class": mainClass = next(args, ++i); break;
                case "--no-fail": fail = false; break;
                case "-h": case "--help": usage(0); return;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    usage(64);
                    return;
            }
        }
        if (inner == null || out == null || agents.isEmpty()) {
            usage(64);
            return;
        }

        JarCoatSpec spec = new JarCoatSpec(inner, out).mainClass(mainClass).failOnAgentError(fail);
        for (String a : agents) {
            int eq = a.indexOf('=');
            spec.addAgent(eq < 0 ? new AgentSpec(Paths.get(a), null)
                    : new AgentSpec(Paths.get(a.substring(0, eq)), a.substring(eq + 1)));
        }
        for (String w : JarCoatBuilder.build(spec)) {
            System.err.println("warning: " + w);
        }
        System.out.println("Wrote " + out);
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) {
            System.err.println("Missing value for " + args[i - 1]);
            usage(64);
        }
        return args[i];
    }

    private static void usage(int code) {
        System.err.println("usage: jarcoat-cli --inner <app.jar> --out <coated.jar> "
                + "--agent <agent.jar>[=options] [--agent ...] [--main-class <fqcn>] [--no-fail]");
        System.exit(code);
    }
}
