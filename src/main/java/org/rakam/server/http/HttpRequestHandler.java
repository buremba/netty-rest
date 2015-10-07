package org.rakam.server.http;

public interface HttpRequestHandler {
    void handle(RakamHttpRequest request);
}
