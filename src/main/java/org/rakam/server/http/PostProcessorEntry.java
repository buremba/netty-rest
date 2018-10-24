package org.rakam.server.http;

import java.lang.reflect.Method;
import java.util.function.Predicate;

public class PostProcessorEntry {

    private final ResponsePostProcessor processor;

    public PostProcessorEntry(ResponsePostProcessor processor) {
        this.processor = processor;
    }

    public ResponsePostProcessor getProcessor() {
        return processor;
    }
}
