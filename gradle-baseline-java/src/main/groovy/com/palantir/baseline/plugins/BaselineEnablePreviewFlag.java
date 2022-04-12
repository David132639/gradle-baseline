/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.baseline.plugins;

import com.palantir.baseline.extensions.BaselineJavaVersionExtension;
import java.util.Collections;
import java.util.List;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.external.javadoc.CoreJavadocOptions;
import org.gradle.process.CommandLineArgumentProvider;

public final class BaselineEnablePreviewFlag implements Plugin<Project> {

    private static final String FLAG = "--enable-preview";
    private static final String ENABLE_PREVIEW_PROPERTY = "enablePreview";

    @Override
    public void apply(Project project) {
        // The idea behind registering a single 'extra property' is that other plugins (like
        // sls-packaging) can easily detect this and also also add the --enable-preview jvm arg.
        project.getExtensions().getExtraProperties().set(ENABLE_PREVIEW_PROPERTY, project.provider(() -> {
            BaselineJavaVersionExtension javaVersionExtension =
                    project.getExtensions().findByType(BaselineJavaVersionExtension.class);
            if (javaVersionExtension != null) {
                // We intentionally prefer NOT to enable feature preview in 'library' jars because this would force
                // all consumers to also add the '--enable-preview' flag to their distribution's JVM args
                return !BaselineJavaVersions.isLibrary(project, javaVersionExtension);
            } else {
                return true;
            }
        }));

        Provider<Boolean> enablePreview = shouldEnablePreview(project);

        project.getPlugins().withId("java", _unused -> {
            project.getTasks().withType(JavaCompile.class, t -> {
                List<CommandLineArgumentProvider> args = t.getOptions().getCompilerArgumentProviders();
                args.add(new MaybeEnablePreview(enablePreview)); // mutation is gross, but it's the gradle convention
            });
            project.getTasks().withType(Test.class, t -> {
                t.getJvmArgumentProviders().add(new MaybeEnablePreview(enablePreview));
            });
            project.getTasks().withType(JavaExec.class, t -> {
                t.getJvmArgumentProviders().add(new MaybeEnablePreview(enablePreview));
            });

            // sadly we have to use afterEvaluate because the Javadoc task doesn't support passing in providers
            project.afterEvaluate(_unused2 -> {
                if (enablePreview.get()) {
                    JavaVersion sourceCompat = project.getConvention()
                            .getPlugin(JavaPluginConvention.class)
                            .getSourceCompatibility();
                    project.getTasks().withType(Javadoc.class, t -> {
                        CoreJavadocOptions options = (CoreJavadocOptions) t.getOptions();

                        // Yes truly javadoc wants a single leading dash, other javac wants a double leading dash.
                        // We also have to use these manual string options because they don't have first-class methods
                        // yet (e.g. https://github.com/gradle/gradle/issues/12898)
                        options.addBooleanOption("-enable-preview", true);
                        options.addStringOption("source", sourceCompat.getMajorVersion());
                    });
                }
            });
        });
    }

    public static Provider<Boolean> shouldEnablePreview(Project project) {
        return project.provider(() -> {
            Object maybeProvider =
                    project.getExtensions().getExtraProperties().getProperties().get(ENABLE_PREVIEW_PROPERTY);
            if (maybeProvider == null) {
                return false;
            }

            return ((Provider<Boolean>) maybeProvider).get();
        });
    }

    private static class MaybeEnablePreview implements CommandLineArgumentProvider {
        private final Provider<Boolean> shouldEnable;

        MaybeEnablePreview(Provider<Boolean> shouldEnable) {
            this.shouldEnable = shouldEnable;
        }

        @Override
        public Iterable<String> asArguments() {
            return shouldEnable.get() ? Collections.singletonList(FLAG) : Collections.emptyList();
        }
    }
}
