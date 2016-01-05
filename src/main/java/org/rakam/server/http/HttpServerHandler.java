package org.rakam.server.http;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.ConcurrentSet;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;


public class HttpServerHandler extends ChannelInboundHandlerAdapter {
    protected RakamHttpRequest request;
    RouteMatcher routes;
    private StringBuilder body = new StringBuilder(2 << 15);
    private static String EMPTY_BODY = "";

    public HttpServerHandler(RouteMatcher routes) {
        this.routes = routes;
    }

    RakamHttpRequest createRequest(ChannelHandlerContext ctx) {
        return new RakamHttpRequest(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof io.netty.handler.codec.http.HttpRequest) {
            this.request = createRequest(ctx);
            this.request.setRequest((io.netty.handler.codec.http.HttpRequest) msg);
            if (HttpHeaders.is100ContinueExpected(request)) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
            } else {
                routes.handle(request);
            }
        }else
        if (msg instanceof LastHttpContent) {
            HttpContent chunk = (HttpContent) msg;
            if (chunk.content().isReadable()) {
                String s = chunk.content().toString(CharsetUtil.UTF_8);
                if (body == null || body.length() == 0) {
                    request.handleBody(s);
                } else {
                    body.append(s);
                    request.handleBody(body.toString());
                }
                body.delete(0, body.length());
                chunk.release();
            } else {
                // even if body content is empty, call request.handleBody method.
                if (request.getBodyHandler()!=null) {
                    request.handleBody(EMPTY_BODY);
                }
            }
        } else if (msg instanceof HttpContent) {
            HttpContent chunk = (HttpContent) msg;
            if (chunk.content().isReadable()) {
                String s = chunk.content().toString(CharsetUtil.UTF_8);
                if (body == null) {
                    body = new StringBuilder(s);
                } else {
                    body.append(s);
                }
                chunk.release();
            }

        } else if (msg instanceof WebSocketFrame) {
            routes.handle(ctx, (WebSocketFrame) msg);
        } else {
//            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    protected static class DebugHttpServerHandler extends ChannelInboundHandlerAdapter {
        private final ConcurrentSet<ChannelHandlerContext> activeChannels;
        private final HttpServerHandler serverHandler;
        final static AttributeKey<Integer> START_TIME = AttributeKey.valueOf("/start_time");

        public DebugHttpServerHandler(ConcurrentSet<ChannelHandlerContext> activeChannels, HttpServerHandler handler) {
            this.activeChannels = activeChannels;
            this.serverHandler = handler;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            activeChannels.add(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            activeChannels.remove(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            serverHandler.channelRead(ctx, msg);

            if (msg instanceof io.netty.handler.codec.http.HttpRequest) {
                ctx.attr(RouteMatcher.PATH).set(serverHandler.request.path());
                ctx.attr(START_TIME).set((int) (System.currentTimeMillis()/1000));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}


