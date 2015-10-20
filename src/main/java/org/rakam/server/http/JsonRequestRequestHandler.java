package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static java.lang.String.format;
import static org.rakam.server.http.HttpServer.handleRequest;
import static org.rakam.server.http.HttpServer.returnError;

public class JsonRequestRequestHandler implements HttpRequestHandler{
    private final static InternalLogger LOGGER = InternalLoggerFactory.getInstance(HttpServer.class);

    private final ObjectMapper mapper;
    private final List<HttpServer.IRequestParameter> bodyParams;
    private final MethodHandle methodHandle;
    private final HttpService service;
    private final boolean isAsync;

    public JsonRequestRequestHandler(ObjectMapper mapper, List<HttpServer.IRequestParameter> bodyParams, MethodHandle methodHandle, HttpService service, boolean isAsync) {
        this.mapper = mapper;
        this.bodyParams = bodyParams;
        this.methodHandle = methodHandle;
        this.service = service;
        this.isAsync = isAsync;
    }

    @Override
    public void handle(RakamHttpRequest request) {
        request.bodyHandler(new Consumer<String>() {
            @Override
            public void accept(String body) {
                ObjectNode node;
                try {
                    node = (ObjectNode) mapper.readTree(body);
                } catch (ClassCastException e) {
                    request.response(HttpServer.bodyError, BAD_REQUEST).end();
                    return;
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

                Object[] values = new Object[bodyParams.size() + 1];
                values[0] = service;
                for (int i = 0; i < bodyParams.size(); i++) {
                    HttpServer.IRequestParameter param = bodyParams.get(0);
                    Object value = param.extract(node, request.headers());
                    if (param.required() && (value == null || value == NullNode.getInstance())) {
                        returnError(request, param.name() + " "+ param.in() + " parameter is required", BAD_REQUEST);
                        return;
                    }

                    values[i+1] = value;
                }

                Object invoke;
                try {
                    invoke = methodHandle.invokeWithArguments(values);
                } catch (Throwable e) {
                    LOGGER.error("Error while processing request", e);
                    request.response(INTERNAL_SERVER_ERROR.reasonPhrase(), INTERNAL_SERVER_ERROR).end();
                    return;
                }

                handleRequest(mapper, isAsync, service, invoke, request);
            }
        });
    }
}
