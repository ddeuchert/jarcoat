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
package org.jarcoat.maven;

import org.jarcoat.core.AgentSpec;
import org.jarcoat.core.JarCoatBuilder;
import org.jarcoat.core.JarCoatSpec;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

/**
 * Wraps the project's executable jar (already built by maven-jar-plugin, shade, assembly, ...) in a
 * JarCoat outer jar that starts the configured agents before the application's main class.
 *
 * <p>Declare agent jars as dependencies (typically {@code provided} scope, so they are not packaged
 * into your app) and reference them by groupId/artifactId, or point at a file with {@code <file>}.
 * The wrapped jar is attached with the {@code jarcoat} classifier by default.
 */
@Mojo(name = "wrap", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class WrapMojo extends AbstractMojo {

    /** One embedded agent. */
    public static class AgentConfig {
        @Parameter private String groupId;
        @Parameter private String artifactId;
        @Parameter private String classifier;
        /** Alternative to coordinates: a path to an agent jar. */
        @Parameter private File file;
        /** Option string, as after '=' in -javaagent:path=options. */
        @Parameter private String options;
    }

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.finalName}", readonly = true, required = true)
    private String finalName;

    @Component
    private MavenProjectHelper projectHelper;

    /** The jar to wrap. Defaults to the project's main artifact (so run after the jar/shade/repackage step). */
    @Parameter
    private File innerJar;

    /** Agents to embed, started in the order listed. */
    @Parameter
    private List<AgentConfig> agents;

    /** Override the inner jar's Main-Class. */
    @Parameter
    private String mainClass;

    /** Classifier for the wrapped jar. */
    @Parameter(defaultValue = "jarcoat")
    private String classifier;

    /** Abort the JVM if an agent fails to start (default), or log and continue. */
    @Parameter(defaultValue = "true")
    private boolean failOnAgentError;

    /** Attach the wrapped jar to the project so install/deploy publish it. */
    @Parameter(defaultValue = "true")
    private boolean attach;

    @Parameter(property = "jarcoat.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("JarCoat: skipped");
            return;
        }
        if (agents == null || agents.isEmpty()) {
            throw new MojoExecutionException("JarCoat: configure at least one <agent>");
        }
        File inner = innerJar != null ? innerJar : project.getArtifact().getFile();
        if (inner == null || !inner.isFile()) {
            throw new MojoExecutionException("JarCoat: no inner jar to wrap. Run this goal after the step that builds "
                    + "the executable jar, or set <innerJar>.");
        }

        File out = new File(outputDirectory, finalName + "-" + classifier + ".jar");
        JarCoatSpec spec = new JarCoatSpec(inner.toPath(), out.toPath())
                .mainClass(mainClass)
                .failOnAgentError(failOnAgentError);
        for (AgentConfig a : agents) {
            spec.addAgent(new AgentSpec(resolve(a).toPath(), a.options));
        }

        try {
            for (String w : JarCoatBuilder.build(spec)) {
                getLog().warn("JarCoat: " + w);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("JarCoat: failed to build " + out + ": " + e.getMessage(), e);
        }
        getLog().info("JarCoat: wrapped " + inner.getName() + " with " + agents.size() + " agent(s) -> " + out.getName());

        if (attach) {
            projectHelper.attachArtifact(project, "jar", classifier, out);
        }
    }

    private File resolve(AgentConfig a) throws MojoExecutionException {
        if (a.file != null) {
            if (!a.file.isFile()) {
                throw new MojoExecutionException("JarCoat: agent file not found: " + a.file);
            }
            return a.file;
        }
        if (a.groupId == null || a.artifactId == null) {
            throw new MojoExecutionException("JarCoat: <agent> needs <groupId>+<artifactId> or <file>");
        }
        List<String> seen = new ArrayList<>();
        for (Artifact art : project.getArtifacts()) {
            seen.add(art.getGroupId() + ":" + art.getArtifactId());
            boolean match = a.groupId.equals(art.getGroupId()) && a.artifactId.equals(art.getArtifactId())
                    && (a.classifier == null || a.classifier.equals(art.getClassifier()));
            if (match && art.getFile() != null) {
                return art.getFile();
            }
        }
        throw new MojoExecutionException("JarCoat: agent " + a.groupId + ":" + a.artifactId
                + " is not a dependency of this project (resolved: " + seen + ")");
    }
}
