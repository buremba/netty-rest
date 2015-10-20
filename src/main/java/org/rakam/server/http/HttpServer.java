package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.swagger.models.Swagger;
import io.swagger.util.Json;
import io.swagger.util.PrimitiveType;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.HeaderParam;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;

import javax.ws.rs.Path;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static org.rakam.server.http.util.Lambda.produceLambda;

public class HttpServer {
    private static String REQUEST_HANDLER_ERROR_MESSAGE = "Request handler method %s.%s couldn't converted to request handler lambda expression: \n %s";
    private static final ObjectMapper DEFAULT_MAPPER;
    static final String bodyError;

    static {
        DEFAULT_MAPPER = new ObjectMapper();
        try {
            bodyError =  DEFAULT_MAPPER.writeValueAsString(errorMessage("Body must be an json object.", BAD_REQUEST));
        } catch (JsonProcessingException e) {
            throw Throwables.propagate(e);
        }
    }

    public final RouteMatcher routeMatcher;
    private final static InternalLogger LOGGER = InternalLoggerFactory.getInstance(HttpServer.class);
    private final Swagger swagger;

    private final ObjectMapper mapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel channel;


    public HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices, Swagger swagger, EventLoopGroup eventLoopGroup, ObjectMapper mapper) {
        this.routeMatcher = new RouteMatcher();

        this.workerGroup = requireNonNull(eventLoopGroup, "eventLoopGroup is null");
        this.swagger = requireNonNull(swagger, "swagger is null");
        this.mapper = mapper;

        this.bossGroup = new NioEventLoopGroup(1);
        registerEndPoints(requireNonNull(httpServicePlugins, "httpServices is null"));
        registerWebSocketPaths(requireNonNull(websocketServices, "webSocketServices is null"));
        routeMatcher.add(HttpMethod.GET, "/api/swagger.json", request -> {
            String content;

            try {
                content = Json.mapper().writeValueAsString(swagger);
            } catch (JsonProcessingException e) {
                request.response("Error").end();
                return;
            }

            request.response(content).end();
        });
    }

    public HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices, Swagger swagger) {
        this(httpServicePlugins, websocketServices, swagger, new NioEventLoopGroup(), DEFAULT_MAPPER);
    }

    public void setNotFoundHandler(HttpRequestHandler handler) {
        routeMatcher.noMatch(handler);
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
        ImmutableMap<Class, PrimitiveType> mappings = ImmutableMap.<Class, PrimitiveType>builder()
                .put(LocalDate.class, PrimitiveType.DATE)
                .put(Duration.class, PrimitiveType.STRING)
                .put(Instant.class, PrimitiveType.DATE_TIME)
                .build();
        SwaggerReader reader = new SwaggerReader(swagger, mapper, mappings);
        httpServicePlugins.forEach(service -> {

            reader.read(service.getClass());
            if (!service.getClass().isAnnotationPresent(Path.class)) {
                throw new IllegalStateException(format("HttpService class %s must have javax.ws.rs.Path annotation", service.getClass()));
            }
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
                                if (jsonRequest != null && httpMethod == HttpMethod.GET) {
                                    throw new IllegalStateException("JsonRequest annotation can only be used with POST requests");
                                } else if (jsonRequest != null && httpMethod == HttpMethod.POST) {
                                    mapped = true;

                                    if (method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class) != null) {
                                        handler = createPostRequestHandler(service, method);
                                    } else {
                                        handler = createParametrizedPostRequestHandler(service, method);
                                    }
                                } else if (httpMethod == HttpMethod.GET && !method.getReturnType().equals(void.class)) {
                                    mapped = true;
                                    handler = createGetRequestHandler(service, method);
                                } else {
                                    handler = generateRequestHandler(service, method);
                                }
                            } catch (Throwable e) {
//                                throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
//                                        method.getClass().getName(), method.getName(), e));
                                throw new RuntimeException(e.getMessage());
                            }

                            microRouteMatcher.add(lastPath.equals("/") ? "" : lastPath, httpMethod, handler);
                        }
                    }
                    if (!mapped && jsonRequest != null) {
//                        throw new IllegalStateException(format("Methods that have @JsonRequest annotation must also include one of HTTPStatus annotations. %s", method.toString()));
                        try {
                            if (method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class) != null) {
                                microRouteMatcher.add(lastPath, HttpMethod.POST, createPostRequestHandler(service, method));
                            } else {
                                microRouteMatcher.add(lastPath, HttpMethod.POST, createParametrizedPostRequestHandler(service, method));
                            }
                        } catch (Throwable e) {
                            throw new RuntimeException(format(REQUEST_HANDLER_ERROR_MESSAGE,
                                    method.getDeclaringClass().getName(), method.getName(), e));
                        }
                    }
                }
            }
        });
    }

    public interface IRequestParameter {
         <T> T extract(ObjectNode node, HttpHeaders headers);
         boolean required();
         String name();
         String in();
    }

    private class HeaderParameter implements IRequestParameter {
        public final String name;
        public final boolean required;

        public HeaderParameter(String name, boolean required) {
            this.name = name;
            this.required = required;
        }

        @Override
        public <T> T extract(ObjectNode node, HttpHeaders headers) {
            return (T) headers.get(name);
        }

        @Override
        public boolean required() {
            return required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String in() {
            return "header";
        }
    }

    private class BodyParameter implements IRequestParameter {
        public final String name;
        public final Type type;
        public final boolean required;

        private BodyParameter(String name, Type type, boolean required) {
            this.name = name;
            this.type = type;
            this.required = required;
        }

        public <T> T extract(ObjectNode node, HttpHeaders headers) {
            JsonNode value = node.get(name);
            if(value == null) {
                return null;
            }
            return mapper.convertValue(value, mapper.constructType(type));
        }

        @Override
        public boolean required() {
            return required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String in() {
            return "body";
        }
    }

    private HttpRequestHandler createParametrizedPostRequestHandler(HttpService service, Method method)
            throws JsonProcessingException {
        ArrayList<IRequestParameter> bodyParams = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(ApiParam.class)) {
                ApiParam apiParam = parameter.getAnnotation(ApiParam.class);
                bodyParams.add(new BodyParameter(apiParam.name(), parameter.getParameterizedType(), apiParam == null ? false : apiParam.required()));
            }
            else if(parameter.isAnnotationPresent(HeaderParam.class)) {
                HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
                bodyParams.add(new HeaderParameter(headerParam.value(), headerParam.required()));
            }
            else {
                bodyParams.add(new BodyParameter(parameter.getName(), parameter.getParameterizedType(), false));
            }
        }

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        if (bodyParams.size() == 0) {
            return new HttpRequestHandler() {
                @Override
                public void handle(RakamHttpRequest request) {
                    Object invoke;
                    try {
                        invoke = method.invoke(service);
                    } catch (IllegalAccessException e) {
                        request.response("not ok").end();
                        return;
                    } catch (InvocationTargetException e) {
                        requestError(e.getCause(), request);
                        return;
                    }

                    handleRequest(mapper, isAsync, service, invoke, request);
                }
            };
        }
        MethodHandle methodHandle;

        try {
            methodHandle = lookup().unreflect(method);
        } catch (IllegalAccessException e) {
            throw Throwables.propagate(e);
        }

        return new JsonRequestRequestHandler(mapper, bodyParams, methodHandle, service, isAsync);
    }

    static void handleRequest(ObjectMapper mapper, boolean isAsync, HttpService service, Object invoke, RakamHttpRequest request) {
        if (isAsync) {
            handleAsyncJsonRequest(mapper, service, request, (CompletionStage) invoke);
        } else {
            try {
                request.response(mapper.writeValueAsString(invoke)).end();
            } catch (JsonProcessingException e) {
                returnError(request, "error while serializing response: " + e.getMessage(), INTERNAL_SERVER_ERROR);
            }
        }
    }

    static void requestError(Throwable ex, RakamHttpRequest request) {
        if (ex instanceof HttpRequestException) {
            HttpResponseStatus statusCode = ((HttpRequestException) ex).getStatusCode();
            returnError(request, ex.getMessage(), statusCode);
        } else {
            LOGGER.error("An uncaught exception raised while processing request.", ex);
            returnError(request, "error processing request: " + ex.getMessage(), INTERNAL_SERVER_ERROR);
        }
    }

    private static BiFunction<HttpService, Object, Object> generateJsonRequestHandler(Method method) throws Throwable {
        return produceLambda(method, BiFunction.class.getMethod("apply", Object.class, Object.class));
    }

    private static HttpRequestHandler generateRequestHandler(HttpService service, Method method)
            throws Throwable {
        if (!method.getReturnType().equals(void.class) ||
                method.getParameterCount() != 1 ||
                !method.getParameterTypes()[0].equals(RakamHttpRequest.class)) {
            throw new IllegalStateException(format("The signature of HTTP request methods must be [void ()]", RakamHttpRequest.class.getCanonicalName()));
        }

        if (Modifier.isStatic(method.getModifiers())) {
            Consumer<RakamHttpRequest> lambda;
            lambda = produceLambda(method, Consumer.class.getMethod("accept", Object.class));
            return request -> {
                try {
                    lambda.accept(request);
                } catch (Exception e) {
                    requestError(e, request);
                }
            };
        } else {
            BiConsumer<HttpService, RakamHttpRequest> lambda;
            lambda = produceLambda(method, BiConsumer.class.getMethod("accept", Object.class, Object.class));
            return request -> {
                try {
                    lambda.accept(service, request);
                } catch (Exception e) {
                    requestError(e, request);
                }
            };
        }
    }

    private HttpRequestHandler createPostRequestHandler(HttpService service, Method method)
            throws Throwable {

        BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);
        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());
        Class<?> jsonClazz = method.getParameterTypes()[0];

        return new HttpRequestHandler() {
            @Override
            public void handle(RakamHttpRequest request) {
                request.bodyHandler(new Consumer<String>() {
                    @Override
                    public void accept(String o) {
                        Object json;
                        try {
                            json = mapper.readValue(o, jsonClazz);
                        } catch (UnrecognizedPropertyException e) {
                            returnError(request, "Unrecognized field: " + e.getPropertyName(), BAD_REQUEST);
                            return;
                        } catch (InvalidFormatException e) {
                            returnError(request, format("Field value couldn't validated: %s ", e.getOriginalMessage()), BAD_REQUEST);
                            return;
                        } catch (JsonMappingException e) {
                            returnError(request, e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), BAD_REQUEST);
                            return;
                        } catch (JsonParseException e) {
                            returnError(request, format("Couldn't parse json: %s ", e.getOriginalMessage()), BAD_REQUEST);
                            return;
                        } catch (IOException e) {
                            returnError(request, format("Error while mapping json: ", e.getMessage()), BAD_REQUEST);
                            return;
                        }
                        if (isAsync) {
                            CompletionStage apply = (CompletionStage) function.apply(service, json);
                            handleAsyncJsonRequest(mapper, service, request, apply);
                        } else {
                            handleJsonRequest(service, request, function, json);
                        }
                    }
                });
            }
        };
    }

    private HttpRequestHandler createGetRequestHandler(HttpService service, Method method)
            throws Throwable {
        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        if (method.getParameterCount() == 0) {
            Function<HttpService, Object> function = produceLambda(method, Function.class.getMethod("apply", Object.class));
            return (request) -> {
                if (isAsync) {
                    CompletionStage apply = (CompletionStage) function.apply(service);
                    handleAsyncJsonRequest(mapper, service, request, apply);
                } else {
                    handleJsonRequest(service, request, function);
                }
            };
        } else {
            BiFunction<HttpService, Object, Object> function = generateJsonRequestHandler(method);
            if (method.getParameterTypes()[0].equals(ObjectNode.class)) {
                return (request) -> {
                    ObjectNode json = generate(request.params());
                    if (isAsync) {
                        CompletionStage apply = (CompletionStage) function.apply(service, json);
                        handleAsyncJsonRequest(mapper, service, request, apply);
                    } else {
                        handleJsonRequest(service, request, function, json);
                    }
                };
            } else {
                return (request) -> {
                    ObjectNode json = generate(request.params());
                    if (isAsync) {
                        CompletionStage apply = (CompletionStage) function.apply(service, json);
                        handleAsyncJsonRequest(mapper, service, request, apply);
                    } else {
                        handleJsonRequest(service, request, function, json);
                    }
                };
            }
        }
    }

    private void handleJsonRequest(HttpService serviceInstance, RakamHttpRequest request, BiFunction<HttpService, Object, Object> function, Object json) {
        try {
            Object apply = function.apply(serviceInstance, json);
            String response = encodeJson(mapper, apply);
            request.response(response).end();
        } catch (HttpRequestException e) {
            HttpResponseStatus statusCode = e.getStatusCode();
            String encode = encodeJson(mapper, errorMessage(e.getMessage(), statusCode));
            request.response(encode, statusCode).end();
        } catch (Exception e) {
            LOGGER.error("An uncaught exception raised while processing request.", e);
            ObjectNode errorMessage = errorMessage("error processing request.", INTERNAL_SERVER_ERROR);
            request.response(encodeJson(mapper, errorMessage), BAD_REQUEST).end();
        }
    }

    private void handleJsonRequest(HttpService serviceInstance, RakamHttpRequest request, Function<HttpService, Object> function) {
        try {
            Object apply = function.apply(serviceInstance);
            String response = encodeJson(mapper, apply);
            request.response(response).end();
        } catch (HttpRequestException e) {
            HttpResponseStatus statusCode = e.getStatusCode();
            String encode = encodeJson(mapper, errorMessage(e.getMessage(), statusCode));
            request.response(encode, statusCode).end();
        } catch (Exception e) {
            LOGGER.error("An uncaught exception raised while processing request.", e);
            ObjectNode errorMessage = errorMessage("error processing request.", INTERNAL_SERVER_ERROR);
            request.response(encodeJson(mapper, errorMessage), BAD_REQUEST).end();
        }
    }

    private static String encodeJson(ObjectMapper mapper, Object apply) {
        try {
            return mapper.writeValueAsString(apply);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("couldn't serialize object", e);
        }
    }

    private static void handleAsyncJsonRequest(ObjectMapper mapper, HttpService serviceInstance, RakamHttpRequest request, CompletionStage apply) {
        apply.whenComplete(new BiConsumer<Object, Throwable>() {
            @Override
            public void accept(Object result, Throwable ex) {
                if (ex != null) {
                    if (ex instanceof HttpRequestException) {
                        HttpResponseStatus statusCode = ((HttpRequestException) ex).getStatusCode();
                        String encode = encodeJson(mapper, errorMessage(ex.getMessage(), statusCode));
                        request.response(encode, statusCode).end();
                    } else {
                        request.response(ex.getMessage()).end();
                    }
                } else {
                    if (result instanceof String) {
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

    public void bind(String host, int port)
            throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch)
                            throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast("httpCodec", new HttpServerCodec());
                        p.addLast("serverHandler", new HttpServerHandler(routeMatcher));
                    }
                });

        channel = b.bind(host, port).sync().channel();
    }

    public void stop() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public static void returnError(RakamHttpRequest request, String message, HttpResponseStatus statusCode) {
        ObjectNode obj = DEFAULT_MAPPER.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode.code());

        String bytes;
        try {
            bytes = DEFAULT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException();
        }
        request.response(bytes, statusCode)
                .headers().set("Content-Type", "application/json; charset=utf-8");
        request.end();
    }

    public static ObjectNode errorMessage(String message, HttpResponseStatus statusCode) {
        return DEFAULT_MAPPER.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode.code());
    }

    public <T> void handleJsonPostRequest(RakamHttpRequest request, Consumer<T> consumer, Class<T> clazz) {
        request.bodyHandler(jsonStr -> {
            T data;
            try {
                data = mapper.readValue(jsonStr, clazz);
            } catch (IOException e) {
                returnError(request, "invalid request", BAD_REQUEST);
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