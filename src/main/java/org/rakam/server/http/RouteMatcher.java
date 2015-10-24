package org.rakam.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RouteMatcher {
    HashMap<PatternBinding, HttpRequestHandler> routes = new HashMap();
    private HttpRequestHandler noMatch = request -> request.response("404", HttpResponseStatus.NOT_FOUND).end();
    public final AttributeKey<String> PATH = AttributeKey.valueOf("/path");
    private List<Map.Entry<PatternBinding, HttpRequestHandler>> prefixRoutes = new LinkedList<>();

    public void handle(ChannelHandlerContext ctx, WebSocketFrame frame) {
        String path = ctx.attr(PATH).get();
        final Object handler = routes.get(new PatternBinding(HttpMethod.GET, path));
        if (handler != null) {
            if(handler instanceof WebSocketService) {
                ((WebSocketService) handler).handle(ctx, frame);
            }
        } else {
            // TODO: WHAT TO DO?
            ctx.close();
        }
    }

    public void handle(RakamHttpRequest request) {
        String path = cleanPath(request.path());
        int lastIndex = path.length() - 1;
        if(lastIndex > 0 && path.charAt(lastIndex) == '/')
            path = path.substring(0, lastIndex);
        // TODO: Make it optional
        if(request.getMethod() == HttpMethod.OPTIONS) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            request.response(response).end();
            return;
        }

        final HttpRequestHandler handler = routes.get(new PatternBinding(request.getMethod(), path));
        if (handler != null) {
            if(handler instanceof WebSocketService) {
                request.context().attr(PATH).set(path);
            }
            handler.handle(request);
        } else {
            for (Map.Entry<PatternBinding, HttpRequestHandler> prefixRoute : prefixRoutes) {
                if(path.startsWith(prefixRoute.getKey().pattern)) {
                    prefixRoute.getValue().handle(request);
                    return;
                }
            }
            noMatch.handle(request);
        }
    }

    private String cleanPath(String path) {
        StringBuilder builder = new StringBuilder();
        boolean edge = false;
        int length = path.length();
        for (int i = 0; i < length; i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if(!edge)
                    builder.append(c);
                edge = true;
            } else {
                builder.append(c);
                edge = false;
            }
        }
        return builder.toString();
    }

    public void add(String path, WebSocketService handler) {
        PatternBinding key = new PatternBinding(HttpMethod.GET, path);
        routes.put(key, handler);
    }

    public void add(HttpMethod method, String path, HttpRequestHandler handler) {
        if(path.endsWith("*")) {
            String substring = path.substring(0, path.length() - 1);
            routes.put(new PatternBinding(method, substring), handler);
            prefixRoutes.add(new AbstractMap.SimpleImmutableEntry<>(new PatternBinding(method, substring), handler));
        }else {
            routes.put(new PatternBinding(method, path), handler);
        }
    }

    public void noMatch(HttpRequestHandler handler) {
        noMatch = handler;
    }

    public static class PatternBinding {
        final HttpMethod method;
        final String pattern;

        private PatternBinding(HttpMethod method, String pattern) {
            this.method = method;
            this.pattern = pattern;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PatternBinding)) return false;

            PatternBinding that = (PatternBinding) o;

            if (!method.equals(that.method)) return false;
            if (!pattern.equals(that.pattern)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = method.hashCode();
            result = 31 * result + pattern.hashCode();
            return result;
        }
    }

    public static class MicroRouteMatcher {
        private final RouteMatcher routeMatcher;
        private String path;

        public MicroRouteMatcher(RouteMatcher routeMatcher, String path) {
            this.routeMatcher = routeMatcher;
            this.path = path;
        }

        public void add(String lastPath, HttpMethod method, HttpRequestHandler handler) {
            Objects.requireNonNull(path, "path is not configured");
            routeMatcher.add(method, path.equals("/") ? lastPath : path + lastPath, handler);
        }
    }
}