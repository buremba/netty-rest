package org.rakam.server.http;

import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.cookie.ServerCookieDecoder.STRICT;
import static io.netty.util.CharsetUtil.UTF_8;

public class RakamHttpRequest
        implements HttpRequest, Comparable {
    private final static Logger LOGGER = Logger.get(HttpServer.class);
    private final static InputStream REQUEST_DONE_STREAM = new InvalidInputStream();

    private final ChannelHandlerContext ctx;
    private HttpRequest request;
    private FullHttpResponse response;
    private Map<String, String> responseHeaders;
    private List<Cookie> responseCookies;

    @Override
    public boolean equals(Object o) {
        return o == this;
    }

    private Consumer<InputStream> bodyHandler;
    private Set<Cookie> cookies;
    private InputStream body;
    private QueryStringDecoder qs;
    private String remoteAddress;

    public RakamHttpRequest(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    void setRequest(HttpRequest request) {
        this.request = request;
    }

    public String getRemoteAddress() {
        return remoteAddress == null ? ((InetSocketAddress) context().channel().remoteAddress()).getHostString() : remoteAddress;
    }

    HttpRequest getRequest() {
        return request;
    }

    @Override
    public HttpMethod getMethod() {
        return request.getMethod();
    }

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        return request.setMethod(method);
    }

    @Override
    public String getUri() {
        return request.getUri();
    }

    @Override
    public HttpRequest setUri(String uri) {
        return request.setUri(uri);
    }

    @Override
    public HttpVersion getProtocolVersion() {
        return request.getProtocolVersion();
    }

    @Override
    public io.netty.handler.codec.http.HttpRequest setProtocolVersion(HttpVersion version) {
        return request.setProtocolVersion(version);
    }

    @Override
    public HttpHeaders headers() {
        return request.headers();
    }

    public ChannelHandlerContext context() {
        return ctx;
    }

    @Override
    public DecoderResult getDecoderResult() {
        return request.getDecoderResult();
    }

    protected Consumer<InputStream> getBodyHandler() {
        return bodyHandler;
    }

    @Override
    public void setDecoderResult(DecoderResult result) {
        request.setDecoderResult(result);
    }

    public void bodyHandler(Consumer<InputStream> function) {
        bodyHandler = function;
    }

    public RakamHttpRequest response(String content) {
        final ByteBuf byteBuf = Unpooled.wrappedBuffer(content.getBytes(UTF_8));
        response = new DefaultFullHttpResponse(HTTP_1_1, OK, byteBuf);
        return this;
    }

    public Set<Cookie> cookies() {
        if (cookies == null) {
            String header = request.headers().get(COOKIE);
            cookies = header != null ? STRICT.decode(header) : ImmutableSet.of();
        }
        return cookies;
    }

    public RakamHttpRequest response(byte[] content) {
        final ByteBuf byteBuf = Unpooled.copiedBuffer(content);
        response = new DefaultFullHttpResponse(HTTP_1_1, OK, byteBuf);
        return this;
    }

    public RakamHttpRequest response(byte[] content, HttpResponseStatus status) {
        final ByteBuf byteBuf = Unpooled.copiedBuffer(content);
        response = new DefaultFullHttpResponse(HTTP_1_1, status, byteBuf);
        return this;
    }

    public RakamHttpRequest response(String content, HttpResponseStatus status) {
        final ByteBuf byteBuf = Unpooled.wrappedBuffer(content.getBytes(UTF_8));
        response = new DefaultFullHttpResponse(HTTP_1_1, status, byteBuf);
        return this;
    }

    public RakamHttpRequest response(FullHttpResponse response) {
        this.response = response;
        return this;
    }

    public Map<String, List<String>> params() {
        if (qs == null) {
            qs = new QueryStringDecoder(request.getUri());
        }
        return qs.parameters();
    }

    public String path() {
        if (qs == null) {
            qs = new QueryStringDecoder(request.getUri());
        }
        return qs.path();
    }

    public void addResponseCookie(Cookie cookie) {
        if (responseCookies != null) {
            responseCookies = new ArrayList<>();
        }

        responseCookies.add(cookie);
    }

    public void addResponseHeader(String key, String value) {
        if (responseHeaders != null) {
            responseHeaders = new HashMap<>();
        }

        responseHeaders.put(key, value);
    }

    public void end() {
        if (body != null) {
            try {
                body.close();
            } catch (IOException e) {
                LOGGER.error(e);
            }
        } else {
            body = REQUEST_DONE_STREAM;
        }

        if (response == null) {
            response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(new byte[0]));
        }
        boolean keepAlive = HttpHeaders.isKeepAlive(request);

        String origin = request.headers().get(ORIGIN);
        if (origin != null) {
            response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        }

        if (responseCookies != null) {
            for (Cookie cookie : responseCookies) {
                response.headers().add(SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
            }
        }

        if (responseHeaders != null) {
            for (Map.Entry<String, String> header : responseHeaders.entrySet()) {
                response.headers().add(header.getKey(), header.getValue());
            }
        }

        if (keepAlive) {
            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);

            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    void handleBody(InputStream body) {
        if (body == REQUEST_DONE_STREAM) {
            try {
                body.close();
            } catch (IOException e) {
                LOGGER.error(e);
            }
        }
        this.body = body;
        if (bodyHandler != null) {
            bodyHandler.accept(body);
        }
    }

    InputStream getBody() {
        return body;
    }

    public StreamResponse streamResponse(HttpResponse response, Duration retryDuration) {
        StreamResponse streamResponse = streamResponse(response);

        ByteBuf msg = Unpooled.wrappedBuffer(("retry:" + retryDuration.toMillis() + "\n\n").getBytes(UTF_8));
        ctx.writeAndFlush(msg);
        return streamResponse;
    }

    public StreamResponse streamResponse() {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, "text/event-stream");
        response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        return streamResponse(response);
    }

    public StreamResponse streamResponse(HttpResponse response) {
        ctx.writeAndFlush(response);
        return new StreamResponse(ctx);
    }

    void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    @Override
    public int compareTo(Object o) {
        return o == null ? -1 : (o == this ? 0 : 1);
    }

    public class StreamResponse {
        private final ChannelHandlerContext ctx;
        private ChannelFuture lastBufferData = null;

        public StreamResponse(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        public synchronized StreamResponse send(String event, String data) {
            if (ctx.isRemoved()) {
                throw new IllegalStateException();
            }
            ByteBuf msg = Unpooled.wrappedBuffer(("event:" + event + "\ndata: " + data.replaceAll("\n", "\ndata: ") + "\n\n").getBytes(UTF_8));
            lastBufferData = ctx.writeAndFlush(msg);
            return this;
        }

        public boolean isClosed() {
            return ctx.isRemoved();
        }

        public void listenClose(Runnable runnable) {
            ctx.channel().closeFuture().addListener(future -> runnable.run());
        }

        public synchronized void end() {
            if (ctx.isRemoved()) {
                return;
            }

            if (lastBufferData != null) {
                lastBufferData.addListener(ChannelFutureListener.CLOSE);
            } else {
                ctx.close();
            }
        }
    }

    // if request.end() is called before Netty fetches the body data, handleBody()
    // will be called after request.end() and it needs to release the buffer immediately.
    // this class is used to identify if the request is ended.
    private static class InvalidInputStream
            extends InputStream {

        @Override
        public int read()
                throws IOException {
            throw new UnsupportedOperationException();
        }
    }
}
