package org.rakam.server.http;

import io.netty.handler.codec.http.FullHttpResponse;

public interface ResponsePostProcessor {
    void handle(FullHttpResponse response);
}
