package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.rakam.server.http.annotations.JsonRequest;

import javax.ws.rs.Path;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.lang.String.format;
import static org.rakam.server.http.util.Lambda.produceLambda;

/**
 * Created by buremba on 20/12/13.
 */
public class HttpServer {
    private static String REQUEST_HANDLER_ERROR_MESSAGE = "Request handler method %s.%s couldn't converted to request handler lambda expression: \n %s";

    public final RouteMatcher routeMatcher;

    EventLoopGroup bossGroup;
    EventLoopGroup workerGroup;
    private Channel channel;

    private final static ObjectMapper mapper = new ObjectMapper();

    public HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices, EventLoopGroup eventLoopGroup) {
        this.routeMatcher = new RouteMatcher();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = eventLoopGroup;

        registerEndPoints(httpServicePlugins);
        registerWebSocketPaths(websocketServices);
    }

    public HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices) {
        this(httpServicePlugins, websocketServices, new NioEventLoopGroup());
    }

    private void registerWebSocketPaths(Set<WebSocketService> webSocketServices) {
        webSocketServices.forEach(service -> {
            String path = service.getClass().getAnnotation(Path.class).value();
            if (path == null) {
                throw new IllegalStateException(format("Classes that implement WebSocketService must have %s annotation.",
                        Path.class.getCanonicalName()));
            }
            routeMatcher.add(path, service);
        });
    }


    private void registerEndPoints(Set<HttpService> httpServicePlugins) {
        httpServicePlugins.forEach(service -> {
            String mainPath = service.getClass().getAnnotation(Path.class).value();
            if (mainPath == null) {
                throw new IllegalStateException(format("Classes that implement HttpService must have %s annotation.", Path.class.getCanonicalName()));
            }
            RouteMatcher.MicroRouteMatcher microRouteMatcher = new RouteMatcher.MicroRouteMatcher(routeMatcher, mainPath);
            for (Method method : service.getClass().getMethods()) {
                Path annotation = method.getAnnotation(Path.class);

                if (annotation != null) {
                    String lastPath = annotation.value();
                    JsonRequest jsonRequest = method.getAnnotation(JsonRequest.class);
                    boolean mapped = false;
                    for (Annotation ann : method.getAnnotations()) {
                        javax.ws.rs.HttpMethod methodAnnotation = ann.annotationType().getAnnotation(javax.ws.rs.HttpMethod.class);

                        if (methodAnnotation != null) {
                            HttpRequestHandler handler = null;
                            HttpMethod httpMethod = HttpMethod.valueOf(methodAnnotation.value());
                            try {
                                if (jsonRequest == null) {
                                    handler = generateRequestHandler(service, method);
                                } else if (httpMethod == HttpMethod.POST) {
                                    mapped = true;
                                    handler = createPostRequestHandler(service, method);
                                } else if (httpMethod == HttpMethod.GET) {
                                    mapped = true;
                                    handler = createGetRequestHandler(service, method);
                                }
                            } catch (Throwable e) {
                                throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
                                        method.getClass().getName(), method.getName(), e));
                            }

                            microRouteMatcher.add(lastPath, httpMethod, handler);
                            if (lastPath.equals("/"))
                                microRouteMatcher.add("", httpMethod, handler);
                        }
                    }
                    if (!mapped && jsonRequest != null) {
//                        throw new IllegalStateException(format("Methods that have @JsonRequest annotation must also include one of HTTPStatus annotations. %s", method.toString()));
                        try {
                            microRouteMatcher.add(lastPath, HttpMethod.POST, createPostRequestHandler(service, method));
                            if (method.getParameterTypes()[0].equals(JsonNode.class))
                                microRouteMatcher.add(lastPath, HttpMethod.GET, createGetRequestHandler(service, method));
                        } catch (Throwable e) {
                            throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
                                    method.getDeclaringClass().getName(), method.getName(), e));
                        }
                    }
                }
            }
        });
    }

    private static BiFunction<HttpService, Object, Object> generateJsonRequestHandler(Method method) throws Throwable {
//        if (!Object.class.isAssignableFrom(method.getReturnType()) ||
//                method.getParameterCount() != 1 ||
//                !method.getParameterTypes()[0].equals(JsonNode.class))
//            throw new IllegalStateException(format("The signature of @JsonRequest methods must be [Object (%s)]", JsonNode.class.getCanonicalName()));

        MethodHandles.Lookup caller = MethodHandles.lookup();
        return produceLambda(caller, method, BiFunction.class.getMethod("apply", Object.class, Object.class));
    }

    private static HttpRequestHandler generateRequestHandler(HttpService service, Method method) throws Throwable {
        if (!method.getReturnType().equals(void.class) ||
                method.getParameterCount() != 1 ||
                !method.getParameterTypes()[0].equals(RakamHttpRequest.class))
            throw new IllegalStateException(format("The signature of HTTP request methods must be [void ()]", RakamHttpRequest.class.getCanonicalName()));

        MethodHandles.Lookup caller = MethodHandles.lookup();

        if (Modifier.isStatic(method.getModifiers())) {
            Consumer<RakamHttpRequest> lambda;
            lambda = produceLambda(caller, method, Consumer.class.getMethod("accept", Object.class));
            return request -> lambda.accept(request);
        } else {
            BiConsumer<HttpService, RakamHttpRequest> lambda;
            lambda = produceLambda(caller, method, BiConsumer.class.getMethod("accept", Object.class, Object.class));
            return request -> lambda.accept(service, request);
        }
    }

    private static HttpRequestHandler createPostRequestHandler(HttpService service, Method method) throws Throwable {

        BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

//        boolean returnString = false;
//        if(isAsync) {
//            Type returnType = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
//            returnString = returnType.equals(String.class);
//        }

        Class<?> jsonClazz = method.getParameterTypes()[0];
        return (request) -> request.bodyHandler(obj -> {
            Object json;
            try {
                json = mapper.readValue(obj, jsonClazz);
            } catch (UnrecognizedPropertyException e) {
                returnError(request, "unrecognized field: " + e.getPropertyName(), 400);
                return;
            } catch (InvalidFormatException e) {
                returnError(request, format("field value couldn't validated: %s ", e.getOriginalMessage()), 400);
                return;
            } catch (IOException e) {
                returnError(request, "json couldn't parsed: " + e.getMessage(), 400);
                return;
            }
            if (isAsync) {
                handleAsyncJsonRequest(service, request, function, json);
            } else {
                handleJsonRequest(service, request, function, json);
            }
        });
    }

    private static HttpRequestHandler createGetRequestHandler(HttpService service, Method method) throws Throwable {
        BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        if (method.getParameterTypes()[0].equals(ObjectNode.class)) {
            return (request) -> {
                ObjectNode json = generate(request.params());

                if (isAsync) {
                    handleAsyncJsonRequest(service, request, function, json);
                } else {
                    handleJsonRequest(service, request, function, json);
                }
            };

        } else {
            return (request) -> {

                ObjectNode json = generate(request.params());
                if (isAsync) {
                    handleAsyncJsonRequest(service, request, function, json);
                } else {
                    handleJsonRequest(service, request, function, json);
                }
            };
        }
    }

    private static void handleJsonRequest(HttpService serviceInstance, RakamHttpRequest request, BiFunction<HttpService, Object, Object> function, Object json) {
        try {
            Object apply = function.apply(serviceInstance, json);
            String response = encodeJson(apply);
            request.response(response).end();
        } catch (RakamException e) {
            int statusCode = e.getStatusCode();
            String encode = encodeJson(errorMessage(e.getMessage(), statusCode));
            request.response(encode, HttpResponseStatus.valueOf(statusCode)).end();
        } catch (Exception e) {
//            LOGGER.error("An uncaught exception raised while processing request.", e);
            ObjectNode errorMessage = errorMessage("error processing request.", HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
            request.response(encodeJson(errorMessage), HttpResponseStatus.BAD_REQUEST).end();
        }
    }

    private static String encodeJson(Object apply) {
        try {
            return mapper.writeValueAsString(apply);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("couldn't serialize object");
        }
    }

    private static void handleAsyncJsonRequest(HttpService serviceInstance, RakamHttpRequest request, BiFunction<HttpService, Object, Object> function, Object json) {
        CompletionStage apply = (CompletionStage) function.apply(serviceInstance, json);
        apply.whenComplete(new BiConsumer<Object, Throwable>() {
            @Override
            public void accept(Object result, Throwable ex) {
                if (ex != null) {
                    if (ex instanceof RakamException) {
                        int statusCode = ((RakamException) ex).getStatusCode();
                        String encode = encodeJson(errorMessage(ex.getMessage(), statusCode));
                        request.response(encode, HttpResponseStatus.valueOf(statusCode)).end();
                    } else {
                        request.response(ex.getMessage()).end();
                    }
                } else {
                    if(result instanceof String) {
                        request.response((String) result).end();
                    } else {
                        try {
                            String encode = mapper.writeValueAsString(result);
                            request.response(encode).end();
                        } catch (JsonProcessingException e) {
                            request.response(format("Couldn't serialize class %s : %s",
                                    result.getClass().getCanonicalName(), e.getMessage())).end();
                        }
                    }
                }
            }
        });
    }

    public void bind(String host, int port) throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("httpCodec", new HttpServerCodec());
                        p.addLast("serverHandler", new HttpServerHandler(routeMatcher));
                    }
                });

        channel = b.bind(host, port).sync().channel();
    }

    public void stop() {
        if (channel != null)
            channel.close().syncUninterruptibly();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public static void returnError(RakamHttpRequest request, String message, Integer statusCode) {
        ObjectNode obj = mapper.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode);

        String bytes;
        try {
            bytes = mapper.writeValueAsString(jsonNodeFactory);
        } catch (JsonProcessingException e) {
            throw new RuntimeException();
        }
        request.response(bytes, HttpResponseStatus.valueOf(statusCode))
                .headers().set("Content-Type", "application/json; charset=utf-8");
        request.end();
    }

    public static ObjectNode errorMessage(String message, int statusCode) {
        return mapper.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode);
    }

    public static <T> void handleJsonPostRequest(RakamHttpRequest request, Consumer<T> consumer, Class<T> clazz) {
        request.bodyHandler(jsonStr -> {
            T data;
            try {
                data = mapper.readValue(jsonStr, clazz);
            } catch (IOException e) {
                returnError(request, "invalid request", 400);
                return;
            }
            consumer.accept(data);
        });
    }

    public static ObjectNode generate(Map<String, List<String>> map) {
        ObjectNode obj = jsonNodeFactory.objectNode();
        for (Map.Entry<String, List<String>> item : map.entrySet()) {
            String key = item.getKey();
            obj.put(key, item.getValue().get(0));
        }
        return obj;
    }

    private static final JsonNodeFactory jsonNodeFactory = new JsonNodeFactory(false);

}