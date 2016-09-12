package org.rakam.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import io.swagger.util.PrimitiveType;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public class HttpServerBuilder {
    private Set<HttpService> httpServices;
    private Set<WebSocketService> websockerServices;
    private Swagger swagger;
    private EventLoopGroup eventLoopGroup;
    private ObjectMapper mapper;
    private boolean debugMode;
    private Map<Class, PrimitiveType> overridenMappings;
    private Builder<PreprocessorEntry> jsonRequestPreprocessors = ImmutableList.builder();
    private Builder<PostProcessorEntry> postProcessorEntryBuilder = ImmutableList.builder();
    private boolean proxyProtocol;
    private ExceptionHandler exceptionHandler;
    private Map<String, IRequestParameterFactory> customRequestParameters;
    private BiConsumer<Method, Operation> swaggerOperationConsumer;
    private boolean useEpoll = true;

    public HttpServerBuilder setHttpServices(Set<HttpService> httpServices) {
        this.httpServices = httpServices;
        return this;
    }

    /**
     * Add processor for @JsonRequest methods.
     *
     * @param preprocessor preprocessor instance that will process request before the method.
     * @param predicate    applies preprocessor only methods that passes this predicate
     * @return if true, method will not be called.
     */
    public HttpServerBuilder addJsonPreprocessor(RequestPreprocessor preprocessor, Predicate<Method> predicate) {
        jsonRequestPreprocessors.add(new PreprocessorEntry(preprocessor, predicate));
        return this;
    }

    public HttpServerBuilder addPostProcessor(ResponsePostProcessor processor, Predicate<Method> predicate) {
        postProcessorEntryBuilder.add(new PostProcessorEntry(processor, predicate));
        return this;
    }

    public HttpServerBuilder setWebsockerServices(Set<WebSocketService> websockerServices) {
        this.websockerServices = websockerServices;
        return this;
    }

    public HttpServerBuilder setSwagger(Swagger swagger) {
        this.swagger = swagger;
        return this;
    }

    public HttpServerBuilder setEventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
        return this;
    }

    public HttpServerBuilder setMapper(ObjectMapper mapper) {
        this.mapper = mapper;
        return this;
    }

    public HttpServerBuilder setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        return this;
    }

    public HttpServerBuilder setSwaggerOperationProcessor(BiConsumer<Method, Operation> consumer) {
        this.swaggerOperationConsumer = consumer;
        return this;
    }

    public HttpServerBuilder setProxyProtocol(boolean proxyProtocol) {
        this.proxyProtocol = proxyProtocol;
        return this;
    }

    public HttpServerBuilder setOverridenMappings(Map<Class, PrimitiveType> overridenMappings) {
        this.overridenMappings = overridenMappings;
        return this;
    }

    public HttpServerBuilder setUseEpollIfPossible(boolean useEpoll) {
        this.useEpoll = useEpoll;
        return this;
    }

    public HttpServerBuilder setExceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    public HttpServerBuilder setCustomRequestParameters(Map<String, IRequestParameterFactory> customRequestParameters) {
        this.customRequestParameters = customRequestParameters;
        return this;
    }

    public HttpServer build() {
        if (eventLoopGroup == null) {
            eventLoopGroup = useEpoll ? new EpollEventLoopGroup() : new NioEventLoopGroup();
        }
        if (swagger == null) {
            swagger = new Swagger();
        }
        if (websockerServices == null) {
            websockerServices = ImmutableSet.of();
        }
        if (customRequestParameters == null) {
            customRequestParameters = ImmutableMap.of();
        }
        return new HttpServer(
                httpServices,
                websockerServices,
                swagger,
                eventLoopGroup,
                jsonRequestPreprocessors.build(),
                postProcessorEntryBuilder.build(),
                mapper == null ? HttpServer.DEFAULT_MAPPER : mapper,
                overridenMappings,
                exceptionHandler,
                customRequestParameters,
                swaggerOperationConsumer,
                debugMode,
                useEpoll && Epoll.isAvailable(),
                proxyProtocol);
    }

    public interface ExceptionHandler {
        void handle(RakamHttpRequest request, Throwable e);
    }

    public interface IRequestParameterFactory {
        IRequestParameter create(Method method);
    }
}
