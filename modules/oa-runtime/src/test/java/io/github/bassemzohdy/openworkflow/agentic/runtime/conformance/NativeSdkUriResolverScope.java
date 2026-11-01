package io.github.bassemzohdy.openworkflow.agentic.runtime.conformance;

import io.github.bassemzohdy.openworkflow.agentic.runtime.adapter.source.ComponentUriTemplateResolver;
import io.serverlessworkflow.impl.WorkflowUtils;
import io.serverlessworkflow.impl.resources.URITemplateResolver;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Measurement control only: hide exactly our provider descriptor from this thread's
 * ServiceLoader. Other descriptors/classes and all SDK execution remain unmodified. */
final class NativeSdkUriResolverScope implements AutoCloseable {
    private static final String SERVICE = "META-INF/services/" + URITemplateResolver.class.getName();
    private final ClassLoader previous = Thread.currentThread().getContextClassLoader();

    NativeSdkUriResolverScope() {
        ClassLoader nativeServices = new ClassLoader(previous) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (!SERVICE.equals(name)) return super.getResources(name);
                var retained = new ArrayList<URL>();
                int removed = 0;
                for (URL url : Collections.list(super.getResources(name))) {
                    List<String> entries;
                    try (var stream = url.openStream()) {
                        entries = new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                                .map(line -> line.split("#", 2)[0].strip()).filter(line -> !line.isEmpty()).toList();
                    }
                    if (entries.contains(ComponentUriTemplateResolver.class.getName())) {
                        if (!entries.equals(List.of(ComponentUriTemplateResolver.class.getName())))
                            throw new IllegalStateException("Native measurement cannot hide another provider");
                        removed++;
                    } else retained.add(url);
                }
                if (removed != 1) throw new IllegalStateException("Expected exactly one platform URI provider descriptor");
                return Collections.enumeration(retained);
            }
        };
        Thread.currentThread().setContextClassLoader(nativeServices);
        try {
            if (!WorkflowUtils.loadFirst(URITemplateResolver.class).orElseThrow().getClass().getName()
                    .equals("io.serverlessworkflow.impl.template.JaxrsURITemplateResolver"))
                throw new IllegalStateException("Native measurement did not select the pinned SDK resolver");
        } catch (RuntimeException | Error failed) {
            close();
            throw failed;
        }
    }

    @Override public void close() {
        Thread.currentThread().setContextClassLoader(previous);
    }
}
