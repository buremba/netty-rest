package org.rakam.server.http;

import java.lang.reflect.Method;
import java.util.function.Predicate;

public class PreprocessorEntry {
   private final RequestPreprocessor preprocessor;
   private final Predicate<Method> predicate;

    public PreprocessorEntry(RequestPreprocessor preprocessor, Predicate<Method> predicate) {
        this.preprocessor = preprocessor;
        this.predicate = predicate;
    }

    public boolean test(Method method) {
        return predicate.test(method);
    }

    public RequestPreprocessor getPreprocessor() {
        return preprocessor;
    }
}
