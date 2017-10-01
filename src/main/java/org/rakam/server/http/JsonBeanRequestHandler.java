package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.rakam.server.http.util.Lambda;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static org.rakam.server.http.HttpServer.returnError;

public class JsonBeanRequestHandler implements HttpRequestHandler {
    private final ObjectMapper mapper;
    private final JavaType jsonClazz;
    private final boolean isAsync;
    private final HttpService service;
    private final List<RequestPreprocessor> requestPreprocessors;
    private final List<ResponsePostProcessor> postProcessors;
    private final HttpServer httpServer;
    private final BiFunction function;

    public JsonBeanRequestHandler(HttpServer httpServer, ObjectMapper mapper, Method method,
                                  List<RequestPreprocessor> requestPreprocessors,
                                  List<ResponsePostProcessor> postProcessors,
                                  HttpService service) {
        this.httpServer = httpServer;
        this.mapper = mapper;
        this.service = service;
        this.postProcessors = postProcessors;

        function = Lambda.produceLambdaForBiFunction(method);

        isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());
        jsonClazz = mapper.constructType(method.getParameters()[0].getParameterizedType());

        this.requestPreprocessors = requestPreprocessors;
    }

    @Override
    public void handle(RakamHttpRequest request) {
        request.bodyHandler(o -> {
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
                returnError(request, e.getCause() != null ? e.getCause().getClass().getName() + ": " +e.getCause().getMessage() : e.getMessage(), BAD_REQUEST);
                return;
            } catch (JsonParseException e) {
                returnError(request, format("Couldn't parse json: %s ", e.getOriginalMessage()), BAD_REQUEST);
                return;
            }catch (DateTimeParseException e) {
                returnError(request, format("Couldn't parse date value '%s' in json: %s ", e.getParsedString(), e.getMessage()), BAD_REQUEST);
                return;
            } catch (IOException e) {
                returnError(request, format("Error while mapping json: ", e.getMessage()), BAD_REQUEST);
                return;
            }

            try {
                if(!requestPreprocessors.isEmpty()) {
                    for (RequestPreprocessor preprocessor : requestPreprocessors) {
                        preprocessor.handle(request);
                    }
                }
            } catch (Throwable e) {
                httpServer.requestError(e, request, postProcessors);
                return;
            }

            if (isAsync) {
                CompletionStage apply;
                try {
                    apply = (CompletionStage) function.apply(service, json);
                } catch (Throwable e) {
                    httpServer.requestError(e, request, postProcessors);
                    return;
                }
                httpServer.handleAsyncJsonRequest(mapper, request, apply, postProcessors);
            } else {
                httpServer.handleJsonRequest(mapper, service, request, (service) -> function.apply(service, json), postProcessors);
            }
        });
    }
}
