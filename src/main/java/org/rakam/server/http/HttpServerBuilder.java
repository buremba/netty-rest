package org.rakam.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import io.netty.channel.EventLoopGroup;
import io.swagger.models.Swagger;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.function.Predicate;

public class HttpServerBuilder {
    private Set<HttpService> httpServices;
    private Set<WebSocketService> websockerServices;
    private Swagger swagger;
    private EventLoopGroup eventLoopGroup;
    private ObjectMapper mapper;
    private Builder<PreprocessorEntry<ObjectNode>> jsonRequestPreprocessors = ImmutableList.builder();
    private Builder<PreprocessorEntry<RakamHttpRequest>> requestPreprocessors = ImmutableList.builder();

    public HttpServerBuilder setHttpServices(Set<HttpService> httpServices) {
        this.httpServices = httpServices;
        return this;
    }

    /**
     * Add processor for @JsonRequest methods.
     * @param preprocessor preprocessor instance that will processs request before the method.
     * @param predicate applies preprocessor only methods that passes this predicate
     * @return if true, method will not be called.
     */
    public HttpServerBuilder addJsonPreprocessor(RequestPreprocessor<ObjectNode> preprocessor, Predicate<Method> predicate) {
        jsonRequestPreprocessors.add(new PreprocessorEntry<>(preprocessor, predicate));
        return this;
    }

    public HttpServerBuilder addPreprocessor(RequestPreprocessor<RakamHttpRequest> preprocessor, Predicate<Method> predicate) {
        requestPreprocessors.add(new PreprocessorEntry<>(preprocessor, predicate));
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

    public HttpServer build() {
        return new HttpServer(
                httpServices, websockerServices,
                swagger, eventLoopGroup,
                new PreProcessors(requestPreprocessors.build(), jsonRequestPreprocessors.build()),
                mapper == null ? HttpServer.DEFAULT_MAPPER : mapper);
    }
}
