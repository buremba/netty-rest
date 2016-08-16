package org.rakam.server.http;

import com.google.common.collect.Lists;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;

import java.util.List;

public class Response<T> {
    private final T data;
    private final HttpResponseStatus status;
    private List<Cookie> cookies;

    private Response(T data, HttpResponseStatus status) {
        this.data = data;
        this.status = status;
    }

    public HttpResponseStatus getStatus()
    {
        return status;
    }

    public Response addCookie(DefaultCookie cookie) {
        if(cookies == null) {
            cookies = Lists.newArrayList();
        }
        cookies.add(cookie);
        return this;
    }

    public Response addCookie(String name, String value, String domain, Boolean isHttpOnly, Long maxAge, String path, Boolean isSecured) {
        if(cookies == null) {
            cookies = Lists.newArrayList();
        }
        final DefaultCookie defaultCookie = new DefaultCookie(name, value);
        if(domain != null) {
            defaultCookie.setDomain(domain);
        }
        if(isHttpOnly != null) {
            defaultCookie.setHttpOnly(isHttpOnly);
        }
        if(maxAge != null) {
            defaultCookie.setMaxAge(maxAge);
        }
        if(path != null) {
            defaultCookie.setPath(path);
        }
        if(isSecured != null) {
            defaultCookie.setSecure(isSecured);
        }
        cookies.add(defaultCookie);
        return this;
    }

    public Response addCookie(String name, String value) {
        if(cookies == null) {
            cookies = Lists.newArrayList();
        }
        cookies.add(new DefaultCookie(name, value));
        return this;
    }

    public T getData() {
        return data;
    }

    public List<Cookie> getCookies() {
        return cookies;
    }

    public static <T> Response<T> ok(T elem) {
        return new Response<>(elem, HttpResponseStatus.OK);
    }

    public static Response value(Object value, HttpResponseStatus status) {
        return new Response(value, status);
    }
}
