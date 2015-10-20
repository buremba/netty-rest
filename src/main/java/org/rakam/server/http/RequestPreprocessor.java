package org.rakam.server.http;

import io.netty.handler.codec.http.HttpHeaders;

public interface RequestPreprocessor<T> {
    boolean handle(HttpHeaders headers, T bodyData);
}
