package org.rakam.server.http;

import java.lang.reflect.Method;
import java.util.function.Predicate;

public class PostProcessorEntry {

    private final Predicate<Method> predicate;
    private final ResponsePostProcessor processor;

    public PostProcessorEntry(ResponsePostProcessor processor, Predicate<Method> predicate) {
        this.processor = processor;
        this.predicate = predicate;
    }

    public boolean test(Method method) {
        return predicate.test(method);
    }

    public ResponsePostProcessor getProcessor() {
        return processor;
    }
}
