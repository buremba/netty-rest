package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.rakam.server.http.util.Lambda;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static java.lang.String.format;
import static org.rakam.server.http.HttpServer.*;

public class JsonBeanRequestHandler implements HttpRequestHandler {
    private final ObjectMapper mapper;
    private final Class<?> jsonClazz;
    private final boolean isAsync;
    private final BiFunction<HttpService, Object, Object> function;
    private final HttpService service;
    private final List<RequestPreprocessor<Object>> jsonPreprocessors;
    private final List<RequestPreprocessor<RakamHttpRequest>> requestPreprocessors;

    public JsonBeanRequestHandler(ObjectMapper mapper, Method method,
                                  List<RequestPreprocessor<Object>> jsonPreprocessors,
                                  List<RequestPreprocessor<RakamHttpRequest>> requestPreprocessors,
                                  HttpService service) {
        this.mapper = mapper;
        this.service = service;

        function = Lambda.produceLambdaForBiFunction(method);
        isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());
        jsonClazz = method.getParameterTypes()[0];

        this.jsonPreprocessors = jsonPreprocessors;
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
                returnError(request, e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), BAD_REQUEST);
                return;
            } catch (JsonParseException e) {
                returnError(request, format("Couldn't parse json: %s ", e.getOriginalMessage()), BAD_REQUEST);
                return;
            } catch (IOException e) {
                returnError(request, format("Error while mapping json: ", e.getMessage()), BAD_REQUEST);
                return;
            }

            if(!jsonPreprocessors.isEmpty()) {
                for (RequestPreprocessor<Object> preprocessor : jsonPreprocessors) {
                    if(!preprocessor.handle(request.headers(), json)) {
                        return;
                    }
                }
            }

            if(!requestPreprocessors.isEmpty() && !HttpServer.applyPreprocessors(request, requestPreprocessors)) {
                return;
            }

            if (isAsync) {
                CompletionStage apply = (CompletionStage) function.apply(service, json);
                handleAsyncJsonRequest(mapper, request, apply);
            } else {
                handleJsonRequest(mapper, service, request, function, json);
            }
        });
    }
}
