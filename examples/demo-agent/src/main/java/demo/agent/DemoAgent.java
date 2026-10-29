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
package demo.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** A trivial agent: announces itself and reports when the application's Hello class is loaded. */
public final class DemoAgent {

    private DemoAgent() {
    }

    public static void premain(String options, Instrumentation inst) {
        System.out.println("[demo-agent] premain, options=" + options);
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                    ProtectionDomain pd, byte[] bytes) {
                if ("demo/app/Hello".equals(className)) {
                    System.out.println("[demo-agent] saw " + className + " being loaded");
                }
                return null;
            }
        });
    }
}
