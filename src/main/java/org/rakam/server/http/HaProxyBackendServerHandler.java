package org.rakam.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HaProxyBackendServerHandler extends HttpServerHandler {
    public HaProxyBackendServerHandler(RouteMatcher routes) {
        super(routes);
    }

    @Override
    public boolean readMessage(ChannelHandlerContext ctx, Object msg) {
        if(msg instanceof HAProxyMessage) {
            this.request = new RakamHttpRequest(ctx);
            this.request.setRemoteAddress(((HAProxyMessage) msg).sourceAddress());
            return true;
        }

        if (msg instanceof io.netty.handler.codec.http.HttpRequest) {
            this.request.setRequest((io.netty.handler.codec.http.HttpRequest) msg);
            if (HttpHeaders.is100ContinueExpected(request)) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
            } else {
                routes.handle(request);
            }
            return true;
        }

        return false;
    }
}
