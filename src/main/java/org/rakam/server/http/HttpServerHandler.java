package org.rakam.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ConcurrentSet;
import org.rakam.server.http.HttpServerBuilder.ExceptionHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpServerHandler
        extends ChannelInboundHandlerAdapter
{
    private final ExceptionHandler uncaughtExceptionHandler;
    private final long maximumBody;
    protected RakamHttpRequest request;
    RouteMatcher routes;
    private List<ByteBuf> body = new ArrayList<>(2);
    private static InputStream EMPTY_BODY = new ByteArrayInputStream(new byte[] {});

    public HttpServerHandler(RouteMatcher routes, ExceptionHandler uncaughtExceptionHandler, long maximumBody)
    {
        this.routes = routes;
        this.maximumBody = maximumBody;
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

    RakamHttpRequest createRequest(ChannelHandlerContext ctx)
    {
        return new RakamHttpRequest(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception
    {

        if (HttpHeaders.is100ContinueExpected(request)) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
        }

        if (msg instanceof HttpRequest) {
            this.request = createRequest(ctx);
            this.request.setRequest((io.netty.handler.codec.http.HttpRequest) msg);

            if (msg instanceof HttpObject) {
                if (((HttpRequest) msg).getDecoderResult().isFailure()) {
                    Throwable cause = ((HttpRequest) msg).getDecoderResult().cause();
                    if (request == null) {
                        request = createRequest(ctx);
                    }
                    HttpServer.returnError(request, cause.getMessage(), BAD_REQUEST);
                }
            }

            routes.handle(request);
        }
        else if (msg instanceof LastHttpContent) {
            HttpContent chunk = (HttpContent) msg;
            try {
                ByteBuf content = chunk.content();
                if (content.isReadable()) {
                    InputStream input;
                    if (body.size() == 0) {
                        input = new ReferenceCountedByteBufInputStream(content);
                    }
                    else {
                        body.add(content);
                        input = new ChainByteArrayInputStream(body);
                        body = new ArrayList<>(2);
                    }

                    request.handleBody(input);
                }
                else {
                    // even if body content is empty, call request.handleBody method.
                    if (request.getBodyHandler() != null) {
                        request.handleBody(EMPTY_BODY);
                    }
                }
            }
            catch (HttpRequestException e) {
                HttpServer.returnError(request, e.getMessage(), e.getStatusCode());
            }
        }
        else if (msg instanceof HttpContent) {
            HttpContent chunk = (HttpContent) msg;
            ByteBuf content = chunk.content();
            if (content.isReadable()) {
                if (maximumBody > -1) {
                    long value = content.capacity();
                    for (ByteBuf byteBuf : body) {
                        value += byteBuf.capacity();
                    }
                    if (value > maximumBody) {
                        HttpServer.returnError(request, "Body is too large.", REQUEST_ENTITY_TOO_LARGE);
                        ctx.close();
                    }
                }

                body.add(content);
            }
        }
        else if (msg instanceof WebSocketFrame) {
            routes.handle(ctx, (WebSocketFrame) msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
    {
        uncaughtExceptionHandler.handle(request, cause);
        cause.printStackTrace();
        HttpServer.returnError(request, "An error occurred", INTERNAL_SERVER_ERROR);
        ctx.close();
    }

    protected static class DebugHttpServerHandler
            extends ChannelInboundHandlerAdapter
    {
        private final ConcurrentSet<ChannelHandlerContext> activeChannels;
        private final HttpServerHandler serverHandler;
        final static AttributeKey<Integer> START_TIME = AttributeKey.valueOf("/start_time");

        public DebugHttpServerHandler(ConcurrentSet<ChannelHandlerContext> activeChannels, HttpServerHandler handler)
        {
            this.activeChannels = activeChannels;
            this.serverHandler = handler;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx)
                throws Exception
        {
            activeChannels.add(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx)
                throws Exception
        {
            activeChannels.remove(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception
        {
            serverHandler.channelRead(ctx, msg);

            if (msg instanceof io.netty.handler.codec.http.HttpRequest) {
                ctx.attr(RouteMatcher.PATH).set(serverHandler.request.path());
                ctx.attr(START_TIME).set((int) (System.currentTimeMillis() / 1000));
            }
        }
    }

    private static class ReferenceCountedByteBufInputStream
            extends InputStream
    {

        private final ByteBuf buffer;

        public ReferenceCountedByteBufInputStream(ByteBuf buffer)
        {
            this.buffer = buffer;
        }

        @Override
        public int available()
                throws IOException
        {
            return buffer.readableBytes();
        }

        @Override
        public int read()
                throws IOException
        {
            return buffer.readByte();
        }

        @Override
        public int read(byte[] b, int off, int len)
                throws IOException
        {
            int available = available();
            if (available == 0) {
                return -1;
            }

            len = Math.min(available, len);
            buffer.readBytes(b, off, len);
            return len;
        }

        @Override
        public void close()
        {
            buffer.release();
        }
    }

    public static class ChainByteArrayInputStream
            extends InputStream
    {
        private final List<ByteBuf> arrays;
        private int position;
        private ByteBuf cursor;
        private int cursorPos;

        public ChainByteArrayInputStream(List<ByteBuf> arrays)
        {
            this.arrays = arrays;
            reset();
        }

        @Override
        public int available()
        {
            int remanining = cursor.capacity() - position;
            for (int i = cursorPos; i < arrays.size(); i++) {
                remanining += arrays.get(0).capacity();
            }
            return remanining;
        }

        @Override
        public int read()
                throws IOException
        {
            if (cursor.capacity() == position) {
                if (arrays.size() == cursorPos) {
                    return -1;
                }
                cursor = arrays.get(cursorPos++);
                position = 1;
                return cursor.getByte(0);
            }

            return cursor.getByte(position++);
        }

        @Override
        public synchronized void reset()
        {
            cursor = arrays.get(0);
            position = 0;
            cursorPos = 1;
        }

        @Override
        public void close()
                throws IOException
        {
            for (ByteBuf buffer : arrays) {
                buffer.release();
            }
        }
    }
}