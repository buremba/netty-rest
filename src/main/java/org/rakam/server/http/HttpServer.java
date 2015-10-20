package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.rakam.server.http.IRequestParameter.BodyParameter;
import org.rakam.server.http.IRequestParameter.HeaderParameter;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.HeaderParam;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;
import org.rakam.server.http.util.Lambda;

import javax.ws.rs.Path;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static org.rakam.server.http.util.Lambda.produceLambdaForBiConsumer;
import static org.rakam.server.http.util.Lambda.produceLambdaForFunction;

public class HttpServer {
    private final static InternalLogger LOGGER = InternalLoggerFactory.getInstance(HttpServer.class);
    static final ObjectMapper DEFAULT_MAPPER;

    public final RouteMatcher routeMatcher;
    private final Swagger swagger;

    private final ObjectMapper mapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final PreProcessors preProcessors;
    private Channel channel;
    private final ImmutableMap<Class, PrimitiveType> swaggerBeanMappings = ImmutableMap.<Class, PrimitiveType>builder()
            .put(LocalDate.class, PrimitiveType.DATE)
            .put(Duration.class, PrimitiveType.STRING)
            .put(Instant.class, PrimitiveType.DATE_TIME)
            .build();

    static {
        DEFAULT_MAPPER = new ObjectMapper();
    }

    HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices, Swagger swagger, EventLoopGroup eventLoopGroup, PreProcessors preProcessors, ObjectMapper mapper) {
        this.routeMatcher = new RouteMatcher();
        this.preProcessors = preProcessors;
        this.workerGroup = requireNonNull(eventLoopGroup, "eventLoopGroup is null");
        this.swagger = requireNonNull(swagger, "swagger is null");
        this.mapper = mapper;

        this.bossGroup = new NioEventLoopGroup(1);
        registerEndPoints(requireNonNull(httpServicePlugins, "httpServices is null"));
        registerWebSocketPaths(requireNonNull(websocketServices, "webSocketServices is null"));
        routeMatcher.add(HttpMethod.GET, "/api/swagger.json", this::swaggerApiHandle);
    }

    public void setNotFoundHandler(HttpRequestHandler handler) {
        routeMatcher.noMatch(handler);
    }

    private void swaggerApiHandle(RakamHttpRequest request) {
        String content;

        try {
            content = Json.mapper().writeValueAsString(swagger);
        } catch (JsonProcessingException e) {
            request.response("Error").end();
            return;
        }

        request.response(content).end();
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
        SwaggerReader reader = new SwaggerReader(swagger, mapper, swaggerBeanMappings);

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

                if (annotation == null) {
                    continue;
                }

                String lastPath = annotation.value();
                Iterator<HttpMethod> methodExists = Arrays.stream(method.getAnnotations())
                        .filter(ann -> ann.annotationType().isAnnotationPresent(javax.ws.rs.HttpMethod.class))
                        .map(ann -> HttpMethod.valueOf(ann.annotationType().getAnnotation(javax.ws.rs.HttpMethod.class).value()))
                        .iterator();

                final JsonRequest jsonRequest = method.getAnnotation(JsonRequest.class);

                // if no @Path annotation exists and @JsonRequest annotation is present, bind POST httpMethod by default.
                if (!methodExists.hasNext() && jsonRequest != null) {
                    microRouteMatcher.add(lastPath, POST, getJsonRequestHandler(method, service));
                } else {
                    while (methodExists.hasNext()) {
                        HttpMethod httpMethod = methodExists.next();

                        if (jsonRequest != null && httpMethod != POST) {
                            throw new IllegalStateException("@JsonRequest annotation can only be used with POST requests");
                        }

                        HttpRequestHandler handler;
                        if (jsonRequest != null) {
                            handler = getJsonRequestHandler(method, service);
                        } else if (httpMethod == HttpMethod.GET && !method.getReturnType().equals(void.class)) {
                            handler = createGetRequestHandler(service, method);
                        } else {
                            handler = generateRawRequestHandler(service, method);
                        }

                        microRouteMatcher.add(lastPath.equals("/") ? "" : lastPath, httpMethod, handler);
                    }
                }
            }
        });
    }

    private HttpRequestHandler getJsonRequestHandler(Method method, HttpService service) {
        if (method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class) != null) {
            return new JsonBeanRequestHandler(mapper, method,
                    getPreprocessorForJsonRequest(method),
                    getPreprocessorRequest(method),
                    service);
        }

        ArrayList<IRequestParameter> bodyParams = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(ApiParam.class)) {
                ApiParam apiParam = parameter.getAnnotation(ApiParam.class);
                bodyParams.add(new BodyParameter(mapper, apiParam.name(), parameter.getParameterizedType(), apiParam == null ? false : apiParam.required()));
            } else if (parameter.isAnnotationPresent(HeaderParam.class)) {
                HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
                bodyParams.add(new HeaderParameter(headerParam.value(), headerParam.required()));
            } else {
                bodyParams.add(new BodyParameter(mapper, parameter.getName(), parameter.getParameterizedType(), false));
            }
        }

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        if (bodyParams.size() == 0) {
            return request -> {
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

                handleRequest(mapper, isAsync, invoke, request);
            };
        } else {
            // TODO: we may specialize for a number of parameters to avoid generic MethodHandle invoker and use lambda generation instead.
            MethodHandle methodHandle;
            try {
                methodHandle = lookup().unreflect(method);
            } catch (IllegalAccessException e) {
                throw Throwables.propagate(e);
            }

            return new JsonParametrizedRequestHandler(mapper, bodyParams,
                    methodHandle, service,
                    getPreprocessorForJsonRequest(method),
                    getPreprocessorRequest(method), isAsync);
        }
    }

    private List<RequestPreprocessor<ObjectNode>> getPreprocessorForJsonRequest(Method method) {
        return preProcessors.jsonRequestPreprocessors.stream()
                .filter(p -> p.test(method)).map(p -> p.getPreprocessor()).collect(Collectors.toList());
    }

    private List<RequestPreprocessor<RakamHttpRequest>> getPreprocessorRequest(Method method) {
        return preProcessors.requestPreprocessors.stream()
                .filter(p -> p.test(method)).map(p -> p.getPreprocessor()).collect(Collectors.toList());
    }

    static void handleRequest(ObjectMapper mapper, boolean isAsync, Object invoke, RakamHttpRequest request) {
        if (isAsync) {
            handleAsyncJsonRequest(mapper, request, (CompletionStage) invoke);
        } else {
            try {
                request.response(mapper.writeValueAsString(invoke)).end();
            } catch (JsonProcessingException e) {
                returnError(request, "error while serializing response: " + e.getMessage(), INTERNAL_SERVER_ERROR);
            }
        }
    }

    private static HttpRequestHandler generateRawRequestHandler(HttpService service, Method method) {
        if (!method.getReturnType().equals(void.class) ||
                method.getParameterCount() != 1 ||
                !method.getParameterTypes()[0].equals(RakamHttpRequest.class)) {
            throw new IllegalStateException(format("The signature of HTTP request methods must be [void ()]", RakamHttpRequest.class.getCanonicalName()));
        }

        // we don't need to pass service object is the method is static.
        // it's also better for performance since there will be only one object to send the stack.
        if (Modifier.isStatic(method.getModifiers())) {
            Consumer<RakamHttpRequest> lambda;
            lambda = Lambda.produceLambdaForConsumer(method);
            return request -> {
                try {
                    lambda.accept(request);
                } catch (Exception e) {
                    requestError(e, request);
                }
            };
        } else {
            BiConsumer<HttpService, RakamHttpRequest> lambda;
            lambda = Lambda.produceLambdaForBiConsumer(method);
            return request -> {
                try {
                    lambda.accept(service, request);
                } catch (Exception e) {
                    requestError(e, request);
                }
            };
        }
    }

    private HttpRequestHandler createGetRequestHandler(HttpService service, Method method) {
        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        final List<RequestPreprocessor<RakamHttpRequest>> preprocessors = getPreprocessorRequest(method);

        if (method.getParameterCount() == 0) {
            Function<HttpService, Object> function = produceLambdaForFunction(method);
            return (request) -> {
                if(!preprocessors.isEmpty() && !HttpServer.applyPreprocessors(request, preprocessors)) {
                    return;
                }

                if (isAsync) {
                    CompletionStage apply = (CompletionStage) function.apply(service);
                    handleAsyncJsonRequest(mapper, request, apply);
                } else {
                    handleJsonRequest(mapper, service, request, function);
                }
            };
        } else {
            BiFunction<HttpService, Object, Object> function = produceLambdaForBiConsumer(method);

            if (method.getParameterTypes()[0].equals(ObjectNode.class)) {
                return request -> {
                    if(!preprocessors.isEmpty() && !HttpServer.applyPreprocessors(request, preprocessors)) {
                        return;
                    }

                    ObjectNode json = generate(request.params());

                    if (isAsync) {
                        CompletionStage apply = (CompletionStage) function.apply(service, json);
                        handleAsyncJsonRequest(mapper, request, apply);
                    } else {
                        handleJsonRequest(mapper, service, request, function, json);
                    }
                };
            } else {
                return request -> {
                    if(!preprocessors.isEmpty() && !HttpServer.applyPreprocessors(request, preprocessors)) {
                        return;
                    }

                    ObjectNode json = generate(request.params());
                    if (isAsync) {
                        CompletionStage apply = (CompletionStage) function.apply(service, json);
                        handleAsyncJsonRequest(mapper, request, apply);
                    } else {
                        handleJsonRequest(mapper, service, request, function, json);
                    }
                };
            }
        }
    }

    static void handleJsonRequest(ObjectMapper mapper, HttpService serviceInstance, RakamHttpRequest request, BiFunction<HttpService, Object, Object> function, Object json) {
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

    static void handleJsonRequest(ObjectMapper mapper, HttpService serviceInstance, RakamHttpRequest request, Function<HttpService, Object> function) {
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

    static void handleAsyncJsonRequest(ObjectMapper mapper, RakamHttpRequest request, CompletionStage apply) {
        apply.whenComplete((BiConsumer<Object, Throwable>) (result, ex) -> {
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
                        request.response(String.format("Couldn't serialize class %s : %s",
                                result.getClass().getCanonicalName(), e.getMessage())).end();
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

    static boolean applyPreprocessors(RakamHttpRequest request, List<RequestPreprocessor<RakamHttpRequest>> preprocessors) {
        for (RequestPreprocessor<RakamHttpRequest> preprocessor : preprocessors) {
            if(!preprocessor.handle(request.headers(), request)) {
                return false;
            }
        }

        return true;
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

    static void requestError(Throwable ex, RakamHttpRequest request) {
        if (ex instanceof HttpRequestException) {
            HttpResponseStatus statusCode = ((HttpRequestException) ex).getStatusCode();
            returnError(request, ex.getMessage(), statusCode);
        } else {
            LOGGER.error("An uncaught exception raised while processing request.", ex);
            returnError(request, "error processing request: " + ex.getMessage(), INTERNAL_SERVER_ERROR);
        }
    }

    public ObjectNode generate(Map<String, List<String>> map) {
        ObjectNode obj = mapper.createObjectNode();
        for (Map.Entry<String, List<String>> item : map.entrySet()) {
            String key = item.getKey();
            obj.put(key, item.getValue().get(0));
        }
        return obj;
    }
}