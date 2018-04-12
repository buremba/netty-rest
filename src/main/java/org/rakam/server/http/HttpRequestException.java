package org.rakam.server.http;

import com.google.common.collect.ImmutableList;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.server.http.HttpServer.JsonAPIError;

import java.util.List;
import java.util.Map;

public class HttpRequestException extends RuntimeException {
    private final HttpResponseStatus statusCode;
    private final Map<String, Object> meta;
    private final List<JsonAPIError> errors;

    public HttpRequestException(List<JsonAPIError> errors, Map<String, Object> meta, HttpResponseStatus statusCode) {
        super("");
        if (statusCode.code() < 400) {
            throw new IllegalArgumentException("Http response codes that indicates an error are between 400 and 600");
        }

        this.meta = meta;
        this.errors = errors;
        this.statusCode = statusCode;
    }

    public HttpRequestException(String message, HttpResponseStatus statusCode) {
        this(ImmutableList.of(JsonAPIError.title(message)), null, statusCode);
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public List<JsonAPIError> getErrors() {
        return errors;
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