package org.rakam.server.http;

import io.netty.handler.codec.http.HttpHeaders;

public interface RequestPreprocessor<T> {
    void handle(HttpHeaders headers, T bodyData);
}
