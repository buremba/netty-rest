package org.rakam.server.http;

public interface RequestPreprocessor {
    void handle(RakamHttpRequest request);
}
