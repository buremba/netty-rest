package org.rakam.server.http;

/**
 * Created by buremba <Burak Emre Kabakcı> on 17/04/15 04:35.
 */
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