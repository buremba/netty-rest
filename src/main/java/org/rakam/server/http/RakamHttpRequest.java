package org.rakam.server.http;

import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.cookie.ServerCookieDecoder.STRICT;
import static io.netty.util.CharsetUtil.UTF_8;

public class RakamHttpRequest implements HttpRequest {
    private final ChannelHandlerContext ctx;
    private HttpRequest request;
    private FullHttpResponse response;
    private Consumer<String> bodyHandler;
    private Set<Cookie> cookies;
    private String body;
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

    protected Consumer<String> getBodyHandler() {
        return bodyHandler;
    }

    @Override
    public void setDecoderResult(DecoderResult result) {
        request.setDecoderResult(result);
    }

    public void bodyHandler(Consumer<String> function) {
        bodyHandler = function;
    }

    public RakamHttpRequest response(String content) {
        final ByteBuf byteBuf = Unpooled.wrappedBuffer(content.getBytes(UTF_8));
        response = new DefaultFullHttpResponse(HTTP_1_1, OK, byteBuf);
        return this;
    }

    public Set<Cookie> cookies() {
        if(cookies == null) {
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

    public void end() {
        if(response == null) {
            response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(new byte[0]));
        }
        boolean keepAlive = HttpHeaders.isKeepAlive(request);

        for (Map.Entry<String, String> entry : request.headers()) {
            if (entry.getKey().equals("Origin")) {
                response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, entry.getValue());
                break;
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

    void handleBody(String body) {
        this.body = body;
        if (bodyHandler != null) {
            bodyHandler.accept(body);
        }
    }

    String getBody() {
        return body;
    }

    public StreamResponse streamResponse() {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, "text/event-stream");
        response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        ctx.writeAndFlush(response);
        return new StreamResponse(ctx);
    }

    void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public class StreamResponse {
        private final ChannelHandlerContext ctx;

        public StreamResponse(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        public StreamResponse send(String event, String data) {
            if(ctx.isRemoved()){
                throw new IllegalStateException();
            }
            ByteBuf msg = Unpooled.wrappedBuffer(("event:"+event + "\ndata: " + data.replaceAll("\n", "\ndata: ") + "\n\n").getBytes(UTF_8));
            ctx.writeAndFlush(msg);
            return this;
        }

        public boolean isClosed() {
            return ctx.isRemoved();
        }

        public void listenClose(Runnable runnable) {
            ctx.channel().closeFuture().addListener(future -> runnable.run());
        }

        public void end() {
            ctx.close().awaitUninterruptibly();
        }
    }
}
