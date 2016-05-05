package org.rakam.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import io.netty.channel.EventLoopGroup;
import io.swagger.models.Swagger;
import io.swagger.util.PrimitiveType;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class HttpServerBuilder {
    private Set<HttpService> httpServices;
    private Set<WebSocketService> websockerServices;
    private Swagger swagger;
    private EventLoopGroup eventLoopGroup;
    private ObjectMapper mapper;
    private boolean debugMode;
    private Map<Class, PrimitiveType> overridenMappings;
    private Builder<PreprocessorEntry<ObjectNode>> jsonRequestPreprocessors = ImmutableList.builder();
    private Builder<PreprocessorEntry<RakamHttpRequest>> requestPreprocessors = ImmutableList.builder();
    private Builder<PreprocessorEntry<Object>> jsonBeanRequestPreprocessors = ImmutableList.builder();
    private boolean proxyProtocol;
    private Builder<PostProcessorEntry> requestPostprocessors = ImmutableList.builder();
    private ExceptionHandler exceptionHandler;

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
    public HttpServerBuilder addJsonPreprocessor(RequestPreprocessor<ObjectNode> preprocessor, Predicate<Method> predicate) {
        jsonRequestPreprocessors.add(new PreprocessorEntry<>(preprocessor, predicate));
        return this;
    }

    /**
     * Add processor for @JsonRequest methods.
     *
     * @param preprocessor preprocessor instance that will processs request before the method.
     * @param predicate    applies preprocessor only methods that passes this predicate
     * @return if true, method will not be called.
     */
    public HttpServerBuilder addJsonBeanPreprocessor(RequestPreprocessor<Object> preprocessor, Predicate<Method> predicate) {
        jsonBeanRequestPreprocessors.add(new PreprocessorEntry<>(preprocessor, predicate));
        return this;
    }

    public HttpServerBuilder addPreprocessor(RequestPreprocessor<RakamHttpRequest> preprocessor, Predicate<Method> predicate) {
        requestPreprocessors.add(new PreprocessorEntry<>(preprocessor, predicate));
        return this;
    }


    public HttpServerBuilder addPostProcessor(ResponsePostProcessor processor, Predicate<Method> predicate) {
        requestPostprocessors.add(new PostProcessorEntry(processor, predicate));
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

    public HttpServerBuilder setProxyProtocol(boolean proxyProtocol) {
        this.proxyProtocol = proxyProtocol;
        return this;
    }

    public HttpServerBuilder setOverridenMappings(Map<Class, PrimitiveType> overridenMappings) {
        this.overridenMappings = overridenMappings;
        return this;
    }

    public HttpServerBuilder setExceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    public HttpServer build() {
        return new HttpServer(
                httpServices, websockerServices,
                swagger, eventLoopGroup,
                new PreProcessors(requestPreprocessors.build(), jsonRequestPreprocessors.build(), jsonBeanRequestPreprocessors.build()),
                requestPostprocessors.build(),
                mapper == null ? HttpServer.DEFAULT_MAPPER : mapper,
                overridenMappings, exceptionHandler, debugMode, proxyProtocol);
    }

    public interface ExceptionHandler {
        void handle(RakamHttpRequest request, Throwable e);
    }
}
