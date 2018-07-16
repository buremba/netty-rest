package org.rakam.server.http;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.thoughtworks.paranamer.BytecodeReadingParanamer;
import io.airlift.log.Logger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.ConcurrentSet;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import io.swagger.util.Json;
import io.swagger.util.PrimitiveType;
import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.jvm.ReflectJvmMapping;
import org.rakam.server.http.HttpServerBuilder.IRequestParameterFactory;
import org.rakam.server.http.IRequestParameter.BodyParameter;
import org.rakam.server.http.IRequestParameter.HeaderParameter;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.BodyParam;
import org.rakam.server.http.annotations.CookieParam;
import org.rakam.server.http.annotations.HeaderParam;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.QueryParam;
import org.rakam.server.http.util.Lambda;

import javax.inject.Named;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.ws.rs.Path;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.SET_COOKIE;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.cookie.ServerCookieEncoder.STRICT;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static org.rakam.server.http.SwaggerReader.getActualType;
import static org.rakam.server.http.util.Lambda.produceLambdaForFunction;

public class HttpServer
{
    private final static Logger LOGGER = Logger.get(HttpServer.class);
    static final ObjectMapper DEFAULT_MAPPER;
    private static BytecodeReadingParanamer PARAMETER_LOOKUP = new BytecodeReadingParanamer();

    public final RouteMatcher routeMatcher;
    private final Swagger swagger;

    private final ObjectMapper mapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final List<PreprocessorEntry> preProcessors;
    private final boolean proxyProtocol;
    private final List<PostProcessorEntry> postProcessors;
    final HttpServerBuilder.ExceptionHandler uncaughtExceptionHandler;
    private final Map<String, IRequestParameterFactory> customParameters;
    private final BiConsumer<Method, Operation> swaggerOperationConsumer;
    private final boolean useEpoll;
    protected final Map<RakamHttpRequest, Long> processingRequests;
    protected final long maximumBodySize;
    private final boolean debugMode;
    private Channel channel;

    private final ImmutableMap<Class, PrimitiveType> swaggerBeanMappings = ImmutableMap.<Class, PrimitiveType>builder()
            .put(LocalDate.class, PrimitiveType.DATE)
            .put(Duration.class, PrimitiveType.STRING)
            .put(Instant.class, PrimitiveType.DATE_TIME)
            .put(ObjectNode.class, PrimitiveType.OBJECT)
            .build();
    protected ConcurrentSet activeChannels;

    static {
        DEFAULT_MAPPER = new ObjectMapper();
    }

    HttpServer(Set<HttpService> httpServicePlugins,
            Set<WebSocketService> websocketServices,
            Swagger swagger, EventLoopGroup eventLoopGroup,
            List<PreprocessorEntry> preProcessors,
            ImmutableList<PostProcessorEntry> postProcessors,
            ObjectMapper mapper,
            Map<Class, PrimitiveType> overriddenMappings,
            HttpServerBuilder.ExceptionHandler exceptionHandler,
            Map<String, IRequestParameterFactory> customParameters,
            BiConsumer<Method, Operation> swaggerOperationConsumer,
            boolean useEpoll,
            boolean proxyProtocol,
            long maximumBodySize,
            boolean debugMode)
    {
        this.routeMatcher = new RouteMatcher();
        this.preProcessors = preProcessors;
        this.workerGroup = requireNonNull(eventLoopGroup, "eventLoopGroup is null");
        this.swagger = requireNonNull(swagger, "swagger is null");
        this.mapper = mapper;
        this.customParameters = customParameters;
        this.swaggerOperationConsumer = swaggerOperationConsumer;
        this.uncaughtExceptionHandler = exceptionHandler == null ? (t, e) -> {
        } : exceptionHandler;
        this.postProcessors = postProcessors;
        this.proxyProtocol = proxyProtocol;
        this.maximumBodySize = maximumBodySize;
        this.debugMode = debugMode;

        this.bossGroup = useEpoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        registerEndPoints(requireNonNull(httpServicePlugins, "httpServices is null"), overriddenMappings);
        registerWebSocketPaths(requireNonNull(websocketServices, "webSocketServices is null"));
        routeMatcher.add(GET, "/api/swagger.json", this::swaggerApiHandle);
        this.useEpoll = useEpoll && Epoll.isAvailable();
        this.processingRequests = new ConcurrentHashMap<>();
    }

    public void setNotFoundHandler(HttpRequestHandler handler)
    {
        routeMatcher.noMatch(handler);
    }

    private void swaggerApiHandle(RakamHttpRequest request)
    {
        String content;

        try {
            content = Json.mapper().writeValueAsString(swagger);
        }
        catch (JsonProcessingException e) {
            request.response("Error").end();
            return;
        }

        request.response(content).end();
    }

    public void markProcessing(RakamHttpRequest request)
    {
        processingRequests.put(request, System.currentTimeMillis());
    }

    public void unmarkProcessing(RakamHttpRequest request)
    {
        processingRequests.remove(request);
    }

    private void registerWebSocketPaths(Set<WebSocketService> webSocketServices)
    {
        webSocketServices.forEach(service -> {
            String path = service.getClass().getAnnotation(Path.class).value();
            if (path == null) {
                throw new IllegalStateException(format("Classes that implement WebSocketService must have %s annotation.",
                        Path.class.getCanonicalName()));
            }
            routeMatcher.add(path, service);
        });
    }

    private void registerEndPoints(Set<HttpService> httpServicePlugins, Map<Class, PrimitiveType> overriddenMappings)
    {
        Map<Class, PrimitiveType> swaggerBeanMappings;
        if (overriddenMappings != null) {
            swaggerBeanMappings = ImmutableMap.<Class, PrimitiveType>builder().putAll(this.swaggerBeanMappings).putAll(overriddenMappings).build();
        }
        else {
            swaggerBeanMappings = this.swaggerBeanMappings;
        }

        SwaggerReader reader = new SwaggerReader(swagger, mapper, swaggerOperationConsumer, swaggerBeanMappings);

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

                method.setAccessible(true);
                String lastPath = annotation.value();
                Iterator<HttpMethod> methodExists = Arrays.stream(method.getAnnotations())
                        .filter(ann -> ann.annotationType().isAnnotationPresent(javax.ws.rs.HttpMethod.class))
                        .map(ann -> HttpMethod.valueOf(ann.annotationType().getAnnotation(javax.ws.rs.HttpMethod.class).value()))
                        .iterator();

                final JsonRequest jsonRequest = method.getAnnotation(JsonRequest.class);

                // if no @Path annotation exists and @JsonRequest annotation is present, bind POST httpMethod by default.
                if (!methodExists.hasNext() && jsonRequest != null) {
                    microRouteMatcher.add(lastPath, POST, getJsonRequestHandler(method, service));
                }
                else {
                    while (methodExists.hasNext()) {
                        HttpMethod httpMethod = methodExists.next();

                        if (jsonRequest != null && httpMethod != POST) {
                            if (Arrays.stream(method.getParameters()).anyMatch(p -> p.isAnnotationPresent(ApiParam.class))) {
                                throw new IllegalStateException("@ApiParam annotation can only be used within POST requests");
                            }
                            if (method.getParameterCount() == 1 && method.getParameters()[0].isAnnotationPresent(BodyParam.class)) {
                                throw new IllegalStateException("@ParamBody annotation can only be used within POST requests");
                            }
                        }

                        HttpRequestHandler handler;
                        if (httpMethod == GET && !method.getReturnType().equals(void.class)) {
                            handler = createGetRequestHandler(service, method);
                        }
                        else if (isRawRequestMethod(method)) {
                            handler = generateRawRequestHandler(service, method);
                        }
                        else {
                            handler = getJsonRequestHandler(method, service);
                        }

                        microRouteMatcher.add(lastPath, httpMethod, handler);
                    }
                }
            }
        });

        swagger.getDefinitions().forEach((k, v) -> Optional.ofNullable(v)
                .map(a -> a.getProperties())
                .ifPresent(a -> a.values().forEach(x -> x.setReadOnly(null))));
    }

    private HttpRequestHandler getJsonRequestHandler(Method method, HttpService service)
    {
        final List<RequestPreprocessor> preprocessorRequest = getPreprocessorRequest(method);
        List<ResponsePostProcessor> postProcessors = getPostPreprocessorsRequest(method);

        if (method.getParameterCount() == 1 && method.getParameters()[0].isAnnotationPresent(BodyParam.class)) {
            return new JsonBeanRequestHandler(this, mapper, method,
                    getPreprocessorRequest(method), postProcessors,
                    service);
        }

        ArrayList<IRequestParameter> bodyParams = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            bodyParams.add(getHandler(parameter, service, method));
        }

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        final List<RequestPreprocessor> preprocessorForJsonRequest = getPreprocessorRequest(method);

        if (bodyParams.size() == 0) {
            final Function<HttpService, Object> lambda = Lambda.produceLambdaForFunction(method);
            return request -> {
                Object invoke;
                try {
                    if (!preprocessorForJsonRequest.isEmpty()) {
                        for (RequestPreprocessor preprocessor : preprocessorForJsonRequest) {
                            preprocessor.handle(request);
                        }
                    }

                    if (!preprocessorRequest.isEmpty()) {
                        HttpServer.applyPreprocessors(request, preprocessorRequest);
                    }

                    invoke = lambda.apply(service);
                }
                catch (Throwable e) {
                    requestError(e.getCause(), request, postProcessors);
                    return;
                }

                handleRequest(mapper, isAsync, invoke, request, postProcessors);
            };
        }
        else {
            // TODO: we may specialize for a number of parameters to avoid generic MethodHandle invoker and use lambda generation instead.
            MethodHandle methodHandle;
            try {
                methodHandle = lookup().unreflect(method);
            }
            catch (IllegalAccessException e) {
                throw Throwables.propagate(e);
            }

            return new JsonParametrizedRequestHandler(this, mapper, bodyParams,
                    methodHandle, postProcessors, service,
                    preprocessorForJsonRequest, isAsync, !method.getReturnType().equals(void.class));
        }
    }

    private List<RequestPreprocessor> getPreprocessorRequest(Method method)
    {
        return preProcessors.stream()
                .filter(p -> p.test(method)).map(p -> p.getPreprocessor()).collect(Collectors.toList());
    }

    private List<ResponsePostProcessor> getPostPreprocessorsRequest(Method method)
    {
        return postProcessors.stream()
                .filter(p -> p.test(method)).map(PostProcessorEntry::getProcessor).collect(Collectors.toList());
    }

    void handleRequest(ObjectMapper mapper, boolean isAsync, Object invoke, RakamHttpRequest request, List<ResponsePostProcessor> postProcessors)
    {
        if (isAsync) {
            handleAsyncJsonRequest(mapper, request, (CompletionStage) invoke, postProcessors);
        }
        else {
            returnJsonResponse(mapper, request, OK, invoke, postProcessors);
        }
    }

    private boolean isRawRequestMethod(Method method)
    {
        return method.getReturnType().equals(void.class) &&
                method.getParameterCount() == 1 &&
                method.getParameterTypes()[0].equals(RakamHttpRequest.class);
    }

    private HttpRequestHandler generateRawRequestHandler(HttpService service, Method method)
    {
        List<RequestPreprocessor> requestPreprocessors = getPreprocessorRequest(method);
        List<ResponsePostProcessor> postProcessors = getPostPreprocessorsRequest(method);

        // we don't need to pass service object is the method is static.
        // it's also better for performance since there will be only one object to send the stack.
        if (Modifier.isStatic(method.getModifiers())) {
            Consumer<RakamHttpRequest> lambda;
            lambda = Lambda.produceLambdaForConsumer(method);
            return request -> {
                try {
                    if (!requestPreprocessors.isEmpty()) {
                        HttpServer.applyPreprocessors(request, requestPreprocessors);
                    }

                    lambda.accept(request);
                }
                catch (Exception e) {
                    requestError(e, request, postProcessors);
                }
            };
        }
        else {
            BiConsumer<HttpService, RakamHttpRequest> lambda;
            lambda = Lambda.produceLambdaForBiConsumer(method);
            return request -> {
                try {
                    lambda.accept(service, request);
                }
                catch (Exception e) {
                    requestError(e, request, postProcessors);
                }
            };
        }
    }

    private HttpRequestHandler createGetRequestHandler(HttpService service, Method method)
    {
        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        final List<RequestPreprocessor> preprocessors = getPreprocessorRequest(method);
        List<ResponsePostProcessor> postProcessors = getPostPreprocessorsRequest(method);

        if (method.getParameterCount() == 0) {
            Function<HttpService, Object> function = produceLambdaForFunction(method);
            return (request) -> {
                try {
                    if (!preprocessors.isEmpty()) {
                        applyPreprocessors(request, preprocessors);
                    }
                }
                catch (Throwable e) {
                    requestError(e, request, postProcessors);
                    return;
                }

                if (isAsync) {
                    CompletionStage apply;
                    try {
                        apply = (CompletionStage) function.apply(service);
                    }
                    catch (Exception e) {
                        requestError(e.getCause(), request, postProcessors);
                        return;
                    }
                    handleAsyncJsonRequest(mapper, request, apply, postProcessors);
                }
                else {
                    handleJsonRequest(mapper, service, request, function, postProcessors);
                }
            };
        }
        else {
            MethodHandle methodHandle;
            try {
                methodHandle = lookup().unreflect(method);
            }
            catch (IllegalAccessException e) {
                throw Throwables.propagate(e);
            }

            IRequestParameter[] parameters = Arrays.stream(method.getParameters())
                    .map(e -> getHandler(e, service, method))
                    .toArray(IRequestParameter[]::new);
            int parameterSize = parameters.length + 1;

            return request -> {
                try {
                    if (!preprocessors.isEmpty()) {
                        HttpServer.applyPreprocessors(request, preprocessors);
                    }
                }
                catch (Throwable e) {
                    requestError(e, request, postProcessors);
                    return;
                }

                ObjectNode json = generate(request.params());
                Object[] objects = new Object[parameterSize];

                objects[0] = service;
                try {
                    for (int i = 0; i < parameters.length; i++) {
                        objects[i + 1] = parameters[i].extract(json, request);
                    }
                }
                catch (Exception e) {
                    requestError(e, request, postProcessors);
                    return;
                }

                if (isAsync) {
                    CompletionStage apply;
                    try {
                        apply = (CompletionStage) methodHandle.invokeWithArguments(objects);
                    }
                    catch (Throwable e) {
                        requestError(e.getCause(), request, postProcessors);
                        return;
                    }
                    handleAsyncJsonRequest(mapper, request, apply, postProcessors);
                }
                else {
                    handleJsonRequest(mapper, request, methodHandle, objects, postProcessors);
                }
            };
        }
    }

    private static final ImmutableMap<Class<?>, Function<String, ?>> PRIMITIVE_MAPPER = ImmutableMap.<Class<?>, Function<String, ?>>builder()
            .put(int.class, Integer::parseInt)
            .put(long.class, Long::parseLong)
            .put(double.class, Double::parseDouble)
            .put(boolean.class, Boolean::parseBoolean)
            .put(float.class, Float::parseFloat)
            .put(Integer.class, Integer::parseInt)
            .put(Long.class, Long::parseLong)
            .put(Double.class, Double::parseDouble)
            .put(Boolean.class, Boolean::parseBoolean)
            .put(Float.class, Float::parseFloat).build();

    private IRequestParameter getHandler(Parameter parameter, HttpService service, Method method)
    {
        if (parameter.isAnnotationPresent(ApiParam.class)) {
            ApiParam apiParam = parameter.getAnnotation(ApiParam.class);
            return new BodyParameter(mapper, apiParam.value(), getActualType(service.getClass(), parameter.getParameterizedType()),
                    apiParam == null ? false : apiParam.required());
        }
        else if (parameter.isAnnotationPresent(HeaderParam.class)) {
            HeaderParam param = parameter.getAnnotation(HeaderParam.class);
            Type actualType = getActualType(service.getClass(), parameter.getParameterizedType());
            if (actualType.equals(String.class) || (actualType instanceof Class && PRIMITIVE_MAPPER.containsKey(actualType))) {
                return new HeaderParameter(param.value(), param.required(),
                        actualType.equals(String.class) ? Function.identity() : PRIMITIVE_MAPPER.get(actualType));
            }
            else {
                if (actualType instanceof Class && ((Class) actualType).isEnum()) {
                    return new HeaderParameter<>(param.value(), param.required(),
                            o -> mapper.convertValue(o, ((Class) actualType)));
                }
                else {
                    throw new IllegalArgumentException(String.format("Invalid HeaderParameter type: %s. Header parameters can only be String, Enum or primitive types", actualType));
                }
            }
        }
        else if (parameter.isAnnotationPresent(CookieParam.class)) {
            CookieParam param = parameter.getAnnotation(CookieParam.class);
            return new IRequestParameter.CookieParameter(param.value(), param.required());
        }
        else if (parameter.isAnnotationPresent(Named.class)) {
            Named param = parameter.getAnnotation(Named.class);
            IRequestParameterFactory iRequestParameter = customParameters.get(param.value());
            if (iRequestParameter == null) {
                throw new IllegalStateException(String.format("Custom parameter %s doesn't have implementation", param.value()));
            }

            return iRequestParameter.create(method);
        }
        else if (parameter.isAnnotationPresent(Named.class)) {
            Named param = parameter.getAnnotation(Named.class);
            IRequestParameterFactory iRequestParameter = customParameters.get(param.value());
            if (iRequestParameter == null) {
                throw new IllegalStateException(String.format("Custom parameter %s doesn't have implementation", param.value()));
            }

            return iRequestParameter.create(method);
        }
        else if (parameter.getType().equals(RakamHttpRequest.class)) {
            return (node, request) -> request;
        }
        else if (parameter.isAnnotationPresent(BodyParam.class)) {
            return new IRequestParameter.FullBodyParameter(mapper, parameter.getParameterizedType());
        }
        else if (parameter.isAnnotationPresent(QueryParam.class)) {
            QueryParam param = parameter.getAnnotation(QueryParam.class);
            Type actualType = getActualType(service.getClass(), parameter.getParameterizedType());
            if (actualType.equals(String.class) || (actualType instanceof Class && PRIMITIVE_MAPPER.containsKey(actualType))) {
                return new IRequestParameter.QueryParameter(param.value(), param.required(),
                        actualType.equals(String.class) ? Function.identity() : PRIMITIVE_MAPPER.get(actualType));
            }
            else {
                if (actualType instanceof Class && ((Class) actualType).isEnum()) {
                    return new IRequestParameter.QueryParameter(param.value(), param.required(),
                            o -> mapper.convertValue(o, ((Class) actualType)));
                }
                else {
                    throw new IllegalArgumentException(String.format("Invalid HeaderParameter type: %s. Header parameters can only be String, Enum or primitive types", actualType));
                }
            }
        }
        else {
            String[] strings;
            try {
                strings = PARAMETER_LOOKUP.lookupParameterNames(method);
            }
            catch (Exception e) {
                throw new IllegalStateException(String.format("Parameter %s in method % does not have @ApiParam annotation and class bytecode doesn't have parameter name info.", parameter.toString(), method.toString()));
            }

            int parameterIndex = Arrays.asList(method.getParameters()).indexOf(parameter);

            boolean required;
            KFunction<?> kotlinFunction = ReflectJvmMapping.getKotlinFunction(method);
            if(kotlinFunction != null) {
                KParameter kParameter = kotlinFunction.getParameters().get(parameterIndex + 1);
                required = !kParameter.getType().isMarkedNullable();
            } else {
                required = false;
            }

            return new BodyParameter(mapper,
                    strings[parameterIndex],
                    parameter.getParameterizedType(), required);
        }
    }

    void handleJsonRequest(ObjectMapper mapper, RakamHttpRequest request, MethodHandle methodHandle, Object[] arguments, List<ResponsePostProcessor> postProcessors)
    {
        try {
            Object apply = methodHandle.invokeWithArguments(arguments);
            returnJsonResponse(mapper, request, OK, apply, postProcessors);
        }
        catch (HttpRequestException e) {
            uncaughtExceptionHandler.handle(request, e);
            HttpResponseStatus statusCode = e.getStatusCode();
            returnJsonResponse(mapper, request, statusCode, new ErrorMessage(e.getErrors(), e.getMeta()), postProcessors);
        }
        catch (Throwable e) {
            uncaughtExceptionHandler.handle(request, e);
            LOGGER.error(e, "An uncaught exception raised while processing request.");

            returnJsonResponse(mapper, request, BAD_REQUEST, getErrorMessage(e), postProcessors);
        }
    }

    void handleJsonRequest(ObjectMapper mapper, HttpService serviceInstance, RakamHttpRequest request, Function<HttpService, Object> function, List<ResponsePostProcessor> postProcessors)
    {
        try {
            Object apply = function.apply(serviceInstance);
            returnJsonResponse(mapper, request, OK, apply, postProcessors);
        }
        catch (HttpRequestException ex) {
            uncaughtExceptionHandler.handle(request, ex);
            HttpResponseStatus statusCode = ex.getStatusCode();
            returnJsonResponse(mapper, request, statusCode, new ErrorMessage(ex.getErrors(), ex.getMeta()), postProcessors);
        }
        catch (Throwable e) {
            uncaughtExceptionHandler.handle(request, e);
            LOGGER.error(e, "An uncaught exception raised while processing request.");
            returnJsonResponse(mapper, request, INTERNAL_SERVER_ERROR,
                    getErrorMessage(e), postProcessors);
        }
    }

    private ErrorMessage getErrorMessage(Throwable e)
    {
        if (debugMode) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            return new ErrorMessage(ImmutableList.of(JsonAPIError.title(e.getClass().getName() + (e.getMessage() == null ? "" : ( ": " + e.getMessage())))), ImmutableMap.of("stacktrace", sw.toString()));
        }
        else {
            return new ErrorMessage(ImmutableList.of(JsonAPIError.title("Error processing request.")), null);
        }
    }

    private static void returnJsonResponse(ObjectMapper mapper, RakamHttpRequest request, HttpResponseStatus status, Object apply, List<ResponsePostProcessor> postProcessors)
    {
        FullHttpResponse response;
        try {
            if (apply instanceof Response) {
                Response responseData = (Response) apply;
                byte[] bytes = mapper.writeValueAsBytes(responseData.getData());

                response = new DefaultFullHttpResponse(HTTP_1_1, responseData.getStatus(), Unpooled.wrappedBuffer(bytes));

                if (responseData.getCookies() != null) {
                    response.headers().add(SET_COOKIE, STRICT.encode(responseData.getCookies()));
                }
            }
            else {
                final ByteBuf byteBuf = Unpooled.wrappedBuffer(mapper.writeValueAsString(apply).getBytes(UTF_8));
                response = new DefaultFullHttpResponse(HTTP_1_1, status, byteBuf);
            }
        }
        catch (JsonProcessingException e) {
            LOGGER.error(e, "Couldn't serialize returned object");
            throw new RuntimeException("couldn't serialize object", e);
        }
        response.headers().set(CONTENT_TYPE, "application/json; charset=utf-8");

        if (postProcessors != null) {
            applyPostProcessors(response, postProcessors);
        }
        request.response(response).end();
    }

    static void applyPostProcessors(FullHttpResponse httpResponse, List<ResponsePostProcessor> postProcessors)
    {
        if (!postProcessors.isEmpty()) {
            for (ResponsePostProcessor postProcessor : postProcessors) {
                postProcessor.handle(httpResponse);
            }
        }
    }

    void handleAsyncJsonRequest(ObjectMapper mapper, RakamHttpRequest request, CompletionStage apply, List<ResponsePostProcessor> postProcessors)
    {
        if (apply == null) {
            NullPointerException e = new NullPointerException();
            uncaughtExceptionHandler.handle(request, e);
            LOGGER.error(e, "Error while processing request. The async method returned null.");
            return;
        }
        apply.whenComplete((BiConsumer<Object, Throwable>) (result, ex) -> {
            if (ex != null) {
                while (ex instanceof CompletionException) {
                    ex = ex.getCause();
                }
                uncaughtExceptionHandler.handle(request, ex);

                requestError(ex, request, postProcessors);
            }
            else {
                returnJsonResponse(mapper, request, OK, result, postProcessors);
            }
        });
    }

    public void bind(String host, int port)
            throws InterruptedException
    {
        channel = bindInternal(host, port).channel();
    }

    public void bindAwait(String host, int port)
            throws InterruptedException
    {
        bind(host, port);
        channel.closeFuture().sync(); // Wait until the channel is closed.
    }

    private ChannelFuture bindInternal(String host, int port)
            throws InterruptedException
    {
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);

        activeChannels = new ConcurrentSet();

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            ObjectName serverName = new ObjectName(SHttpServer.class.getPackage().getName() + ":name=" + SHttpServer.class.getSimpleName());
            mbs.registerMBean(new SHttpServer(this), serverName);
        }
        catch (InstanceAlreadyExistsException | MalformedObjectNameException | NotCompliantMBeanException | MBeanRegistrationException e) {
            LOGGER.warn(e, "Error while registering MBean");
        }

        b.group(bossGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>()
                {
                    @Override
                    protected void initChannel(SocketChannel ch)
                            throws Exception
                    {
                        ChannelPipeline p = ch.pipeline();
                        HttpServerHandler handler;
                        if (proxyProtocol) {
                            p.addLast(new HAProxyMessageDecoder());
                            handler = new HaProxyBackendServerHandler(activeChannels, HttpServer.this);
                        }
                        else {
                            handler = new HttpServerHandler(activeChannels, HttpServer.this);
                        }

                        // make it configurable
                        p.addLast("httpCodec", new HttpServerCodec(36192 * 2, 36192 * 8, 36192 * 16));
                        p.addLast("serverHandler", handler);
                    }
                });

        return b.bind(host, port).sync();
    }

    public void stop()
    {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    static void applyPreprocessors(RakamHttpRequest request, List<RequestPreprocessor> preprocessors)
    {
        for (RequestPreprocessor preprocessor : preprocessors) {
            preprocessor.handle(request);
        }
    }

    public static void returnError(RakamHttpRequest request, String message, HttpResponseStatus statusCode)
    {
        byte[] bytes;
        try {
            bytes = DEFAULT_MAPPER.writeValueAsBytes(new ErrorMessage(ImmutableList.of(JsonAPIError.title(message)), null));
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException();
        }
        request.response(bytes, statusCode).headers().set("Content-Type", "application/json");
        request.end();
    }

    public static void returnError(RakamHttpRequest request, HttpRequestException e)
    {
        byte[] bytes;
        try {
            bytes = DEFAULT_MAPPER.writeValueAsBytes(new ErrorMessage(e.getErrors(), e.getMeta()));
        }
        catch (JsonProcessingException ex) {
            throw new RuntimeException();
        }
        request.response(bytes, e.getStatusCode()).headers().set("Content-Type", "application/json");
        request.end();
    }

    public static class ErrorMessage
    {
        public final List<JsonAPIError> errors;
        public final Map<String, Object> meta;

        @JsonCreator
        public ErrorMessage(@ApiParam("errors") List<JsonAPIError> errors, @ApiParam("meta") Map<String, Object> meta)
        {
            this.errors = errors;
            this.meta = meta;
        }
    }

    public static class JsonAPIError
    {
        public final String id;
        public final List<String> links;
        public final Integer status;
        public final String code;
        public final String title;
        public final String detail;
        public final Map<String, Object> meta;

        public JsonAPIError(String id, List<String> links, Integer status, String code, String title, String detail, Map<String, Object> meta)
        {
            this.id = id;
            this.links = links;
            this.status = status;
            this.code = code;
            this.title = title;
            this.detail = detail;
            this.meta = meta;
        }

        public static JsonAPIError title(String title)
        {
            return new JsonAPIError(null, null, null, null, title, null, null);
        }

        public static JsonAPIError codeTitle(String code, String title)
        {
            return new JsonAPIError(null, null, null, code, title, null, null);
        }

        public static JsonAPIError codeTitleDetail(String code, String title, String detail)
        {
            return new JsonAPIError(null, null, null, code, title, detail, null);
        }
    }

    void requestError(Throwable ex, RakamHttpRequest request, List<ResponsePostProcessor> postProcessors)
    {
        uncaughtExceptionHandler.handle(request, ex);
        if (ex instanceof HttpRequestException) {
            HttpRequestException httpEx = (HttpRequestException) ex;
            HttpResponseStatus statusCode = httpEx.getStatusCode();
            returnJsonResponse(mapper, request, statusCode, new ErrorMessage(httpEx.getErrors(), httpEx.getMeta()), postProcessors);
        }
        else {
            returnJsonResponse(mapper, request, INTERNAL_SERVER_ERROR, getErrorMessage(ex), postProcessors);
            LOGGER.error(ex, "Error while processing request");
        }
    }

    private ObjectNode generate(Map<String, List<String>> map)
    {
        ObjectNode obj = mapper.createObjectNode();
        for (Map.Entry<String, List<String>> item : map.entrySet()) {
            String key = item.getKey();
            obj.put(key, item.getValue().get(0));
        }
        return obj;
    }
}