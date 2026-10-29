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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * {@code Main-Class} of every wrapped jar. Runs after {@link LauncherAgent#agentmain}
 * and hands control to the inner application's real main class.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    public static void main(String[] args) throws Throwable {
        if (!LauncherAgent.started) {
            // Launcher-Agent-Class is only honored for `java -jar`. TODO(M3): optional fork fallback.
            System.err.println("JarCoat: the launcher agent did not run. Start this jar with 'java -jar <file>' "
                    + "(running it via -cp or from an IDE bypasses the embedded agents).");
            System.exit(2);
            return;
        }
        LaunchConfig cfg = LaunchConfig.load();
        Class<?> main = Class.forName(cfg.innerMainClass, true, ClassLoader.getSystemClassLoader());
        Method m = main.getMethod("main", String[].class);
        try {
            m.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause(); // surface the app's own exception, as `java -jar` would
        }
    }
}
