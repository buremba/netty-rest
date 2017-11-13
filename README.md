Netty RESTful Server
=======
Netty-rest is a high performance HTTP and WebSocket server implementation based on Netty. It uses `javax.ws.rs` annotations and generates Java bytecode at runtime in order to provide best performance. It basically maps the java methods to endpoints and takes care of validation, serialization / deserialization, authentication and designed for minimum overhead and maximum performance. It can generate Swagger specification automatically so that you can generate client libraries, API documentation easily.

Here is a simple example:

```
import org.rakam.server.http.HttpServer;
import org.rakam.server.http.HttpServerBuilder;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.*;

import javax.ws.rs.Path;
import java.util.Arrays;
import java.util.HashSet;

public class HttpServerTest {
    public static void main(String[] args) throws Exception {
        HttpServer build = new HttpServerBuilder()
                .setHttpServices(new HashSet<>(Arrays.asList(new CustomHttpServer()))).build();

        build.bindAwait("127.0.0.1", 7847);
    }

    @Path("/")
    public static class CustomHttpServer extends HttpService {
        @JsonRequest
        @ApiOperation(value = "Parameter demo endpoint")
        @Path("/parameter")
        public String testJsonParameter(@ApiParam("param1") String param1, @ApiParam("param2") int param2) {
            return param1 + param2;
        }
    }
}
```

And then run the following CURL command:

```
curl -X POST http://127.0.0.1:7847/parameter \
    -H 'content-type: application/json' \
    -d '{"param1": "Hello", "param2": 2}'
```

If you don't pass one of the parameters, the server will return `400` response, you can also use complex java beans in parameters and method return signature. The library uses Jackson for serialization of the object that you passed and deserialization of the JSON attributes. It will be mapped to the parameters and the method will be invoked for the API calls.

Here is the complete list of examples for basic operations:

```
import org.rakam.server.http.HttpServer;
import org.rakam.server.http.HttpServerBuilder;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.*;

import javax.ws.rs.Path;
import java.util.Arrays;
import java.util.HashSet;

public class HttpServerTest {
    public static void main(String[] args) throws Exception {
        HttpServer build = new HttpServerBuilder()
                .setHttpServices(new HashSet<>(Arrays.asList(new SimpleHttpService()))).build();

        build.bindAwait("127.0.0.1", 7847);
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
```

# Authentication

You can implement API key based authentification easily with custom parameters. Here is a simple example:

```
public class HttpServerTest {
    public static void main(String[] args) throws Exception {
        HttpServer build = new HttpServerBuilder()
                .setCustomRequestParameters(ImmutableMap.of("projectId", method -> (node, request) -> {
                    String apiKey = request.headers().get("api_key");
                    try {
                        return apiKeyService.findProject(apiKey);
                    } catch (NotFoundException e) {
                        throw new HttpRequestException("API key is invalid", HttpResponseStatus.FORBIDDEN);
                    }
                })).build();
                .setHttpServices(new HashSet<>(Arrays.asList(new CustomHttpServer()))).build();

        build.bindAwait("127.0.0.1", 7847);
    }

    @Path("/")
    public static class CustomHttpServer extends HttpService {
        @JsonRequest
        @ApiOperation(value = "Parameter demo endpoint")
        @Path("/list")
        public List<String> testJsonParameter(@Named("projectId") int id) {
            return db.getItemsForProject(id);
        }
    }
}
```


# Request hooks
You can add hooks to API calls before the methods are executed and also after they're executed. Here is an example:

```
HttpServer build = new HttpServerBuilder()
    .setHttpServices(new HashSet<>(Arrays.asList(new SimpleHttpService())))
    .addJsonPreprocessor(new RequestPreprocessor() {
        @Override
        public void handle(RakamHttpRequest request) {
            System.out.println(request.getUri());
        }
    }, (method) -> true)
    .addPostProcessor(new ResponsePostProcessor() {
        @Override
        public void handle(FullHttpResponse response) {
            System.out.println(response.getStatus());
        }
    }, (method) -> true).build();
```

# Websockets
Although the library is designed for RESTFul APIs, it also has support for websockets:

```
public class HttpServerTest {
    public static void main(String[] args) throws Exception {
        HttpServer build = new HttpServerBuilder()
                .setWebsocketServices(new HashSet<>(Arrays.asList(new SimpleWebhookService()))).build();

        build.bindAwait("127.0.0.1", 7847);
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
}
```

# Exception handling

Exception hooks are particularly useful for logging them to your API Exception tracker. If you throw `HttpRequestException` in your code, you can set the API call status code and error message but if the `Exception` is not an instance of `HttpRequestException`, the server will return `500` status code.

```
HttpServer build = new HttpServerBuilder()
      .setHttpServices(new HashSet<>(Arrays.asList(new SimpleHttpService())))
      .setExceptionHandler(new HttpServerBuilder.ExceptionHandler() {
          @Override
          public void handle(RakamHttpRequest request, Throwable e) {

          }
      }).build();
```

# Swagger

The library automatically generates the Swagger spec for you. You can see the specification in `/api/swagger.json` path. Here is a [real example](http://app.rakam.io/api/swagger.json). I also maintaion a [Slate documentation generator](https://github.com/buremba/swagger-slate) from Swagger specification. This library is compatible with the API documentation generator, [here is an example](http://api.rakam.io).

You can set your Swagger instance using `HttpServerBuilder.setSwagger`. Here is an example:

```
Info info = new Info()
        .title("My API Documentation")
        .version("0.1")
        .description("My great API")
        .contact(new Contact().email("contact@product.com"))
        .license(new License()
                .name("Apache License 2.0")
                .url("http://www.apache.org/licenses/LICENSE-2.0.html"));

Swagger swagger = new Swagger().info(info)
        .host("app.myapp.io")
        .basePath("/")
        .tags(ImmutableList.copyOf(tags))
        .securityDefinition("api_key", new ApiKeyAuthDefinition().in(In.HEADER).name("api_key"));

new HttpServerBuilder().setSwagger(swagger).build()
```

# Misc

If you run the library on Linux, it will try to use [Epoll](http://netty.io/wiki/native-transports.html) but you can disable it with `HttpServerBuilder.setUseEpollIfPossible` 

You can also use your own Jackson mapper with `HttpServerBuilder.setMapper`  if you have custom JSON serializers / deserializers.

We also support [Proxy Protocol](https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt) if you run the HTTP server behind the load balancer. You can enable it with `HttpServerBuilder.setProxyProtocol`.

You can take a look at examples in Rakam which heavily uses netty-rest: https://github.com/rakam-io/rakam/blob/master/rakam/src/main/java/org/rakam/plugin/user/UserHttpService.java

# Profiling

The library exposes an MBean called `org.rakam.server.http:name=SHttpServer`. If you attach the JVM instance you can call `getActiveClientCount` and `getActiveRequests` for the list of active request the execution time of each request.

# To be done
- Javadocs
- API usage monitoring tool
