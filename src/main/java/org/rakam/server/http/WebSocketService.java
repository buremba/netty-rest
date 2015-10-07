package org.rakam.server.http;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;

public abstract class WebSocketService implements HttpRequestHandler {
    private WebSocketServerHandshaker handshaker;

    public abstract void onOpen(WebSocketRequest request);
    public abstract void onMessage(ChannelHandlerContext ctx, String message);
    public abstract void onClose(ChannelHandlerContext ctx);

    public void handle(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            onClose(ctx);
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
                    .getName()));
        }

        String msg = ((TextWebSocketFrame) frame).text();
        onMessage(ctx, msg);
    }

    public ChannelFuture send(ChannelHandlerContext ctx, String message) {
        return ctx.channel().writeAndFlush(new TextWebSocketFrame(message));
    }

    @Override
    public void handle(RakamHttpRequest request) {
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                getWebSocketLocation(request), null, true);
        handshaker = wsFactory.newHandshaker(request.getRequest());
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(request.context().channel());
        } else {
            HttpRequest request1 = request.getRequest();
            DefaultFullHttpRequest defaultFullHttpRequest = new DefaultFullHttpRequest(request1.getProtocolVersion(), request1.getMethod(), request1.getUri());
            defaultFullHttpRequest.headers().set(request1.headers());
            handshaker.handshake(request.context().channel(), defaultFullHttpRequest);
            onOpen(new WebSocketRequest(request));
        }
    }

    private static String getWebSocketLocation(RakamHttpRequest req) {
        String location =  req.headers().get(HOST) + req.getUri();
//        if (WebSocketServer.SSL) {
//            return "wss://" + location;
//        } else {
        return "ws://" + location;
//        }
    }

    public static class WebSocketRequest {
        private final RakamHttpRequest request;

        public WebSocketRequest(RakamHttpRequest request) {
            this.request = request;
        }

        public String uri() {
            return request.getUri();
        }

        public Map<String, List<String>> params() {
            return request.params();
        }

        public HttpHeaders headers() {
            return request.headers();
        }

        public ChannelHandlerContext context() {
            return request.context();
        }
    }
}
