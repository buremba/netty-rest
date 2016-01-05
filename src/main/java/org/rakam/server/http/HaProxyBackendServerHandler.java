package org.rakam.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.AttributeKey;

public class HaProxyBackendServerHandler extends HttpServerHandler {
    AttributeKey<String> CLIENT_IP = AttributeKey.valueOf("ip");


    public HaProxyBackendServerHandler(RouteMatcher routes) {
        super(routes);
    }

    @Override
    RakamHttpRequest createRequest(ChannelHandlerContext ctx) {
        RakamHttpRequest request = super.createRequest(ctx);
        String clientIp = ctx.attr(CLIENT_IP).get();
        if(clientIp != null) {
            request.setRemoteAddress(clientIp);
        }
        return request;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if(msg instanceof HAProxyMessage) {
            ctx.attr(CLIENT_IP).set(((HAProxyMessage) msg).sourceAddress());
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
