package org.rakam.server.http;

import java.lang.reflect.Method;
import java.util.function.Predicate;

public class PreprocessorEntry<T> {
   private final RequestPreprocessor<T> preprocessor;
   private final Predicate<Method> predicate;

    public PreprocessorEntry(RequestPreprocessor<T> preprocessor, Predicate<Method> predicate) {
        this.preprocessor = preprocessor;
        this.predicate = predicate;
    }

    public boolean test(Method method) {
        return predicate.test(method);
    }

    public RequestPreprocessor<T> getPreprocessor() {
        return preprocessor;
    }
}
