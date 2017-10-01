import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import io.netty.channel.ChannelHandlerContext;
import org.junit.Test;
import org.rakam.server.http.*;
import org.rakam.server.http.annotations.*;

import javax.inject.Named;
import javax.ws.rs.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class HttpServerTest {

    //    @Test
    public void testName() throws Exception {

        HttpServer build = new HttpServerBuilder()
                .setHttpServices(new HashSet<>(Arrays.asList(new SimpleHttpService())))
                .setUseEpollIfPossible(false)
                .setCustomRequestParameters(ImmutableMap.of("test", method -> (node, request) -> request.getUri())).build();

        build.bindAwait("127.0.0.1", 7847);
    }

    //    @Test
    public void testWebhook() throws Exception {

        HttpServer build = new HttpServerBuilder()
                .setWebsocketServices(new HashSet<>(Arrays.asList(new SimpleWebhookService())))
                .setCustomRequestParameters(ImmutableMap.of("test", method -> (node, request) -> request.getUri())).build();

        build.bindAwait("127.0.0.1", 7847);
    }

    //    @Test
    public void testCustom() throws Exception {
        HttpServer build = new HttpServerBuilder()
                .setHttpServices(new HashSet<>(Arrays.asList(new ParameterHttpService())))
                .setUseEpollIfPossible(false)
                .setCustomRequestParameters(ImmutableMap.of("test", method -> (node, request) -> request.getUri())).build();

        build.bindAwait("127.0.0.1", 7847);
    }

    @Path("/")
    public static class ParameterHttpService extends HttpService {
        @JsonRequest
        @ApiOperation(value = "Bean Demo endpoint")
        @Path("/bean")
        public String testJsonBean(@Named("test") SimpleHttpService.DemoBean demo) {
            return demo.toString();
        }
    }

    public class SimpleWebhookService extends WebSocketService {
        private String id;

        @Override
        public void onOpen(WebSocketRequest request) {
            id = UUID.randomUUID().toString();
            System.out.println(String.format("%s: started", id));
        }

        @Override
        public void onMessage(ChannelHandlerContext ctx, String message) {
            System.out.println(String.format("%s: sent %s", id, message));
        }

        @Override
        public void onClose(ChannelHandlerContext ctx) {
            System.out.println(String.format("%s: closed", id));
        }
    }

    @Path("/")
    public static class SimpleHttpService extends HttpService {
        @JsonRequest
        @ApiOperation(value = "Bean Demo endpoint")
        @Path("/bean")
        public String testJsonBean(@BodyParam DemoBean demo) {
            return demo.toString();
        }

        @JsonRequest
        @ApiOperation(value = "Parameter demo endpoint")
        @Path("/parameter")
        public String testJsonParameter(@ApiParam("param1") String param1, @ApiParam("param2") int param2) {
            return param1 + param2;
        }

        // You can also use CompletableFuture for async operations
        @JsonRequest
        @ApiOperation(value = "Parameter demo endpoint")
        @Path("/future-parameter")
        public CompletableFuture<String> futureTestJsonParameter(@ApiParam("param1") String param1, @ApiParam("param2") Integer param2, @ApiParam(value = "param3", required = false) Long param3) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.complete(param1 + param2 + param3);
            return future;
        }

        @JsonRequest
        @ApiOperation(value = "Parameter demo endpoint")
        @Path("/header-cookie-parameter")
        public CompletableFuture<String> futureTestJsonParameter(@HeaderParam("my-custom-header") String param1, @CookieParam("my-cookie-param") String param2) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.complete(param1 + param2);
            return future;
        }

        @JsonRequest
        @ApiOperation(value = "Raw demo endpoint")
        @Path("/raw")
        public void testJsonParameter(RakamHttpRequest request) {
            request.response("cool").end();
        }

        public static class DemoBean {
            public final String test;

            @JsonCreator
            public DemoBean(@JsonProperty("test") String test) {
                this.test = test;
            }
        }
    }
}
