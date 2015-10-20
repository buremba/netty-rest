package org.rakam.server.http;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public class PreProcessors {
    public final List<PreprocessorEntry<RakamHttpRequest>> requestPreprocessors;
    public final List<PreprocessorEntry<ObjectNode>> jsonRequestPreprocessors;

    public PreProcessors(List<PreprocessorEntry<RakamHttpRequest>> requestPreprocessors,
                         List<PreprocessorEntry<ObjectNode>> jsonRequestPreprocessors) {
        this.requestPreprocessors = requestPreprocessors;
        this.jsonRequestPreprocessors = jsonRequestPreprocessors;
    }
}
