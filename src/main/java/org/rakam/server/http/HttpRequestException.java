package org.rakam.server.http;

public class HttpRequestException extends RuntimeException {
    private final int statusCode;

    public HttpRequestException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    // Stack traces are expensive and we don't need them.
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}