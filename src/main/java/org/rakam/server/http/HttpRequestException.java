package org.rakam.server.http;

import io.netty.handler.codec.http.HttpResponseStatus;

public class HttpRequestException extends RuntimeException {
    private final HttpResponseStatus statusCode;

    public HttpRequestException(String message, HttpResponseStatus statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public HttpResponseStatus getStatusCode() {
        return statusCode;
    }

    // Stack traces are expensive and we don't need them.
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}