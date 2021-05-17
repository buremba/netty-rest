package org.rakam.server.http;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.PrimitiveType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.tags.Tag;
import org.rakam.server.http.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;
import sun.reflect.generics.reflectiveObjects.TypeVariableImpl;

import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class SwaggerReader {
    private final Logger LOGGER = LoggerFactory.getLogger(SwaggerReader.class);

    private final OpenAPI swagger;
    private final ModelConverters modelConverters;
    private final BiConsumer<Method, Operation> swaggerOperationConsumer;

    public SwaggerReader(OpenAPI swagger, ObjectMapper mapper, BiConsumer<Method, Operation> swaggerOperationConsumer, Map<Class, PrimitiveType> externalTypes) {
        this.swagger = swagger;
        modelConverters = new ModelConverters(mapper);
        this.swaggerOperationConsumer = swaggerOperationConsumer;
        modelConverters.addConverter(new ModelResolver(mapper));
        if (externalTypes != null) {
            setExternalTypes(externalTypes);
        }

        Schema errorProperty = modelConverters.readAsProperty(new AnnotatedType(HttpServer.ErrorMessage.class));
        swagger.addExtension("ErrorMessage", modelConverters.read(new AnnotatedType(HttpServer.ErrorMessage.class)).entrySet().iterator().next().getValue());
    }

    private void setExternalTypes(Map<Class, PrimitiveType> externalTypes) {
        // ugly hack until swagger supports adding external classes as primitive types
        try {
            Field externalTypesField = PrimitiveType.class.getDeclaredField("EXTERNAL_CLASSES");
            Field modifiersField = Field.class.getDeclaredField("modifiers");

            modifiersField.setAccessible(true);
            externalTypesField.setAccessible(true);
            modifiersField.set(externalTypesField, externalTypesField.getModifiers() & ~Modifier.FINAL);

            Map<String, PrimitiveType> externalTypesInternal = externalTypes.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().getName(), e -> e.getValue()));
            externalTypesField.set(null, externalTypesInternal);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.warn("Couldn't set external types", e);
        }
    }

    public OpenAPI read(Class cls) {
        return read(cls, "", false, new ArrayList<>());
    }

    protected OpenAPI read(Class<?> cls, String parentPath, boolean readHidden, List<Parameter> parentParameters) {
        Api api = cls.getAnnotation(Api.class);
        javax.ws.rs.Path apiPath = cls.getAnnotation(javax.ws.rs.Path.class);

        if (cls.isAnnotationPresent(IgnoreApi.class)) {
            return swagger;
        }

        // only read if allowing hidden apis OR api is not marked as hidden
        if ((api != null && readHidden) || (api != null && !api.hidden())) {
            // the value will be used as a tag for 2.0 UNLESS a Tags annotation is present
            Set<String> tags = extractTags(api);

            Authorization[] authorizations = api.authorizations();

            List<SecurityRequirement> securities = new ArrayList<>();
            for (Authorization auth : authorizations) {
                if (auth.value() != null && !"".equals(auth.value())) {
                    SecurityRequirement security = new SecurityRequirement();
                    security.addList(auth.value());
                    securities.add(security);
                }
            }

            // parse the method
            Method methods[] = cls.getMethods();
            for (Method method : methods) {
                ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
                javax.ws.rs.Path methodPath = method.getAnnotation(javax.ws.rs.Path.class);

                if (method.isAnnotationPresent(IgnoreApi.class)) {
                    continue;
                }

                String operationPath = getPath(apiPath, methodPath, parentPath);
                if (operationPath != null && apiOperation != null) {
                    String[] pps = operationPath.split("/");
                    String[] pathParts = new String[pps.length];
                    Map<String, String> regexMap = new HashMap<>();

                    for (int i = 0; i < pps.length; i++) {
                        String p = pps[i];
                        if (p.startsWith("{")) {
                            int pos = p.indexOf(":");
                            if (pos > 0) {
                                String left = p.substring(1, pos);
                                String right = p.substring(pos + 1, p.length() - 1);
                                pathParts[i] = "{" + left + "}";
                                regexMap.put(left, right);
                            } else {
                                pathParts[i] = p;
                            }
                        } else {
                            pathParts[i] = p;
                        }
                    }
                    StringBuilder pathBuilder = new StringBuilder();
                    for (String p : pathParts) {
                        if (!p.isEmpty()) {
                            pathBuilder.append("/").append(p);
                        }
                    }
                    operationPath = pathBuilder.toString();

                    Operation operation = null;
                    try {
                        operation = parseMethod(cls, method);
                    } catch (Exception e) {
                        LOGGER.debug("Unable to read method " + method.toString(), e);
                    }

                    if (operation == null) {
                        continue;
                    }

                    if (parentParameters != null) {
                        operation.parameters(parentParameters);
                    }
                    operation.getParameters().stream()
                            .filter(param -> regexMap.get(param.getName()) != null)
                            .forEach(param -> {
                                String pattern = regexMap.get(param.getName());
//                                param.setContent(pattern);
                            });

                    if (isSubResource(method)) {
                        Class<?> responseClass = method.getReturnType();
                        read(responseClass, operationPath, true, operation.getParameters());
                    }

                    String httpMethod = extractOperationMethod(apiOperation, method);


                    // can't continue without a valid http method
                    if (httpMethod != null) {
                        ApiOperation op = method.getAnnotation(ApiOperation.class);
                        if (op != null) {
                            List<String> tagNames = Arrays.stream(op.tags()).filter(tag -> !"".equals(tag)).collect(Collectors.toList());
                            operation.tags(tagNames);
                            tagNames.forEach(it -> swagger.addTagsItem(new Tag().name(it)));
                        }

                        if (operation != null) {
                            Consumes consumesAnn = method.getAnnotation(Consumes.class);
                            if (consumesAnn != null && !Arrays.asList(consumesAnn.value()).stream().anyMatch(a -> a.startsWith("application/json"))) {
                                continue;
                            }
                            if (!apiOperation.consumes().isEmpty() && !apiOperation.consumes().startsWith("application/json")) {
                                continue;
                            }

                            operation.security(securities);

                            PathItem path = swagger.getPaths().get(operationPath);
                            if (path == null) {
                                path = new PathItem();
                                swagger.path(operationPath, path);
                            }

                            swaggerOperationConsumer.accept(method, operation);
                            if (httpMethod.equals("get")) {
                                path.get(operation);
                            } else if (httpMethod.equals("post")) {
                                path.post(operation);
                            } else if (httpMethod.equals("put")) {
                                path.put(operation);

                            } else if (httpMethod.equals("delete")) {
                                path.delete(operation);

                            } else if (httpMethod.equals("options")) {
                                path.options(operation);

                            } else if (httpMethod.equals("patch")) {
                                path.patch(operation);

                            } else {
                                throw new IllegalStateException();
                            }
                        }
                    }
                }
            }
        }
        return swagger;
    }

    protected boolean isSubResource(Method method) {
        Class<?> responseClass = method.getReturnType();
        if (responseClass != null && responseClass.getAnnotation(Api.class) != null) {
            return true;
        }
        return false;
    }

    private static Type getActualReturnType(Method method) {
        if (method.getReturnType().equals(CompletableFuture.class)) {
            Type responseClass = method.getGenericReturnType();

            ParameterizedType type;
            if (responseClass instanceof ParameterizedType) {
                type = (ParameterizedType) responseClass;
            } else {
                try {
                    // TODO: check super classes if this doesn't work.
                    type = (ParameterizedType) method.getDeclaringClass().getSuperclass().getMethod(method.getName(), method.getParameterTypes())
                            .getGenericReturnType();
                } catch (NoSuchMethodException e) {
                    throw Throwables.propagate(e);
                }
            }
            return type.getActualTypeArguments()[0];
        }

        return method.getGenericReturnType();
    }

    private static Class getActualReturnClass(Method method) {
        if (method.getReturnType().equals(CompletableFuture.class)) {
            Type responseClass = method.getGenericReturnType();
            Type type = ((ParameterizedType) responseClass).getActualTypeArguments()[0];
            if (type instanceof ParameterizedType) {
                return (Class) ((ParameterizedType) type).getRawType();
            } else {
                return (Class) type;
            }
        }

        return method.getReturnType();
    }

    protected Set<String> extractTags(Api api) {
        Set<String> output = new LinkedHashSet<>();

        boolean hasExplicitTags = false;
        for (String tag : api.tags()) {
            if (!"".equals(tag)) {
                hasExplicitTags = true;
                output.add(tag);
            }
        }
        if (!hasExplicitTags) {
            // derive tag from api path + description
            String tagString = api.value().replace("/", "");
            if (!"".equals(tagString)) {
                output.add(tagString);
            }
        }
        return output;
    }

    String getPath(javax.ws.rs.Path classLevelPath, javax.ws.rs.Path methodLevelPath, String parentPath) {
        if (classLevelPath == null && methodLevelPath == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        if (parentPath != null && !"".equals(parentPath) && !"/".equals(parentPath)) {
            if (!parentPath.startsWith("/")) {
                parentPath = "/" + parentPath;
            }
            if (parentPath.endsWith("/")) {
                parentPath = parentPath.substring(0, parentPath.length() - 1);
            }

            b.append(parentPath);
        }
        if (classLevelPath != null) {
            b.append(classLevelPath.value());
        }
        if (methodLevelPath != null && !"/".equals(methodLevelPath.value())) {
            String methodPath = methodLevelPath.value();
            if (!methodPath.startsWith("/") && !b.toString().endsWith("/")) {
                b.append("/");
            }
            if (methodPath.endsWith("/")) {
                methodPath = methodPath.substring(0, methodPath.length() - 1);
            }
            b.append(methodPath);
        }
        String output = b.toString();
        if (!output.startsWith("/")) {
            output = "/" + output;
        }
        if (output.endsWith("/") && output.length() > 1) {
            return output.substring(0, output.length() - 1);
        } else {
            return output;
        }
    }

    public Map<String, io.swagger.v3.oas.models.headers.Header> parseResponseHeaders(ResponseHeader[] headers) {
        Map<String, io.swagger.v3.oas.models.headers.Header> responseHeaders = null;
        if (headers != null && headers.length > 0) {
            for (ResponseHeader header : headers) {
                String name = header.name();
                if (!"".equals(name)) {
                    if (responseHeaders == null) {
                        responseHeaders = new HashMap<>();
                    }
                    String description = header.description();
                    Class<?> cls = header.response();
                    String container = header.responseContainer();

                    if (!cls.equals(java.lang.Void.class) && !"void".equals(cls.toString())) {
                        Schema responseProperty;
                        Schema property = modelConverters.readAsProperty(new AnnotatedType(cls));
                        if (property != null) {
                            if ("list".equalsIgnoreCase(container)) {
                                responseProperty = new ArraySchema().items(property);
                            } else {
                                responseProperty = property;
                            }
                            responseProperty.setDescription(description);
                            responseHeaders.put(name, new Header().schema(responseProperty));
                        }
                    }
                }
            }
        }
        return responseHeaders;
    }

    public Operation parseMethod(Class readClass, Method method) {
        Operation operation = new Operation();

        ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
        ApiResponses responseAnnotation = method.getAnnotation(ApiResponses.class);

        String responseContainer = null;

        Type responseClass = null;
        Map<String, io.swagger.v3.oas.models.headers.Header> defaultResponseHeaders = new HashMap<>();

        Api parentApi = (Api) readClass.getAnnotation(Api.class);
        String nickname = !apiOperation.nickname().isEmpty() ? apiOperation.nickname() : parentApi.nickname();
        if (nickname.isEmpty()) {
            nickname = method.getDeclaringClass().getName().replace(".", "_");
        }
        String methodName = method.getName().substring(0, 1).toUpperCase() + method.getName().substring(1);
        String methodIdentifier = nickname.substring(0, 1).toUpperCase() + nickname.substring(1) + methodName;

        if (apiOperation != null) {
            if (apiOperation.hidden()) {
                return null;
            }

            operation.operationId(methodName);

            defaultResponseHeaders = parseResponseHeaders(apiOperation.responseHeaders());

            operation
                    .summary(apiOperation.value())
                    .description(apiOperation.notes());

            if (apiOperation.response() != null && !Void.class.equals(apiOperation.response())) {
                responseClass = apiOperation.response();
            }
            if (!"".equals(apiOperation.responseContainer())) {
                responseContainer = apiOperation.responseContainer();
            }
            if (apiOperation.authorizations() != null) {
                List<SecurityRequirement> securities = new ArrayList<>();
                for (Authorization auth : apiOperation.authorizations()) {
                    if (auth.value() != null && !"".equals(auth.value())) {
                        SecurityRequirement security = new SecurityRequirement();
                        security.addList(auth.value());
                        securities.add(security);
                    }
                }
                operation.security(securities);
            }
        }

        if (responseClass == null && !method.getReturnType().equals(Void.class)) {
            responseClass = getActualReturnType(method);
        }

        if (responseClass != null && !responseClass.equals(java.lang.Void.class)) {
            if (responseClass instanceof Class && TypeToken.class.equals(((Class) responseClass).getSuperclass())) {
                responseClass = ((ParameterizedType) ((Class) responseClass).getGenericSuperclass()).getActualTypeArguments()[0];
            }
            if (isPrimitive(responseClass)) {
                Schema responseProperty;
                Schema property = modelConverters.readAsProperty(new AnnotatedType(responseClass));
                if (property != null) {
                    if ("list".equalsIgnoreCase(responseContainer)) {
                        responseProperty = new ArraySchema().items(property);
                    } else {
                        responseProperty = property;
                    }
                    operation.getResponses().addApiResponse(200, new Response()
                            .description("Successful request")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                }
            } else if (!responseClass.equals(java.lang.Void.class) && !"void".equals(responseClass.toString())) {
                String name = responseClass.getTypeName();
                Schema model = modelConverters.read(new AnnotatedType().type(responseClass)).get(name);
                if (model == null) {
                    Schema p = modelConverters.readAsProperty(new AnnotatedType(responseClass));
                    operation.getResponses().addApiResponse("200", new ApiResponse()
                            .description("Successful request").content(new Content().addMediaType("success", new MediaType().schema(p)))
                            .headers(defaultResponseHeaders));
                } else {
                    model.$ref(responseClass.getTypeName());

                    Schema responseProperty;

                    if ("list".equalsIgnoreCase(responseContainer)) {
                        responseProperty = new ArraySchema();
                    } else if ("map".equalsIgnoreCase(responseContainer)) {
                        responseProperty = new ObjectSchema();
                    } else {
                        responseProperty = new Schema();
                    }
                    responseProperty.setDefault(name);

                    Content main = new Content().addMediaType("main", new MediaType().schema(responseProperty));
                    operation.getResponses().addApiResponse("200", new ApiResponse()
                            .description("Successful operation")
                            .content(main));
                }
            }

            Map<String, Schema> models = modelConverters.readAll(new AnnotatedType(responseClass));
            for (String key : models.keySet()) {
                swagger.schema(key, models.get(key));
            }
        }

        operation.deprecated(method.isAnnotationPresent(Deprecated.class));

        java.lang.reflect.Parameter[] parameters;
        Type explicitType = null;

        String name, reference;
        if (!apiOperation.request().equals(Void.class)) {
            Class<?> clazz = apiOperation.request();
            if (clazz.getSuperclass() != null && clazz.getSuperclass().equals(TypeToken.class)) {
                explicitType = ((ParameterizedType) clazz.getGenericSuperclass()).getActualTypeArguments()[0];
                parameters = null;
                name = null;
                reference = null;
            } else {
                parameters = readApiBody(clazz);
                name = clazz.getSimpleName();
                reference = clazz.getName();
            }
        } else if (Arrays.stream(method.getParameters()).anyMatch(p -> p.isAnnotationPresent(BodyParam.class))) {
            Class type = Arrays.stream(method.getParameters())
                    .filter(p -> p.isAnnotationPresent(BodyParam.class))
                    .findAny().get().getType();

            Type parameterizedType = method.getParameters()[0].getParameterizedType();
            if (modelConverters.readAsProperty(new AnnotatedType(parameterizedType)) instanceof ArraySchema) {
                explicitType = type;
                parameters = null;
                name = null;
                reference = null;
            } else {
                parameters = readApiBody(type);
                name = type.getSimpleName();
                reference = type.getName();
            }
        } else {
            parameters = method.getParameters();
            name = methodIdentifier;
            reference = methodIdentifier;
        }

        if (parameters != null && parameters.length > 0) {
            List<String> params = Arrays.asList("string", "number", "integer", "boolean");

            List<Schema> properties = Arrays.stream(parameters)
                    .map(parameter ->
                    {
                        Type actualType = getActualType(readClass, parameter.getParameterizedType());
                        return parameter.isAnnotationPresent(ApiParam.class) || parameter.isAnnotationPresent(HeaderParam.class) ?
                                modelConverters.readAsProperty(new AnnotatedType().type(actualType)) : null;
                    })
                    .collect(Collectors.toList());
            List<Parameter> list = readMethodParameters(parameters, properties, name, reference);

            operation.setParameters(list);
        } else if (explicitType != null) {
            Schema property = modelConverters.readAsProperty(new AnnotatedType(explicitType));
            operation.setParameters(ImmutableList.of(new Parameter().schema(property)));
        }

        if (operation.getResponses() == null) {
            operation.getResponses().setDefault(new ApiResponse().description("Successful request"));
        }
        return operation;
    }

    public static Type getActualType(Class readClass, Type parameterizedType) {
        // if the parameter has a generic type, it will be read as Object
        // so we need to find the actual implementation and return that type.

        // the generic type may not come from the HttpService class, if it's not keep track of it:
        // ((TypeVariableImpl)parameters[2].getParameterizedType()).getGenericDeclaration().getTypeParameters()[0].getBounds()[0]
        if (parameterizedType instanceof TypeVariableImpl) {
            TypeVariable[] genericParameters = readClass.getSuperclass().getTypeParameters();
            Type[] implementations = ((ParameterizedTypeImpl) readClass.getGenericSuperclass()).getActualTypeArguments();
            for (int i = 0; i < genericParameters.length; i++) {
                if (genericParameters[i].getName().equals(((TypeVariableImpl) parameterizedType).getName())) {
                    return implementations[i];
                }
            }
        }
        return parameterizedType;
    }

    private java.lang.reflect.Parameter[] readApiBody(Class<?> type) {
        List<Constructor<?>> constructors = Arrays.stream(type.getConstructors())
                .sorted((o1, o2) -> {
                    if (o1.isAnnotationPresent(JsonCreator.class)) {
                        return 1;
                    } else {
                        return -1;
                    }
                })
                .collect(Collectors.toList());

        if (constructors.stream().filter((o1) -> o1.isAnnotationPresent(JsonCreator.class)).count() > 1) {
            throw new IllegalArgumentException(format("%s has more then one constructor annotation with @ParamBody. There must be only one.",
                    type.getSimpleName()));
        }

        if (constructors.isEmpty()) {
            throw new IllegalArgumentException(format("%s doesn't have any constructor annotation with @JsonCreator.",
                    type.getSimpleName()));
        }

        java.lang.reflect.Parameter[] parameters = constructors.get(0).getParameters();

        return parameters;
    }

    private List<Parameter> readMethodParameters(java.lang.reflect.Parameter[] parameters, List<Schema> properties, String name, String reference) {
        ImmutableList.Builder<Parameter> builder = ImmutableList.builder();


        for (int i = 0; i < properties.size(); i++) {
            Schema property = properties.get(i);
            java.lang.reflect.Parameter parameter = parameters[i];
            if (parameter.isAnnotationPresent(Named.class)) {
                continue;
            } else if (parameter.isAnnotationPresent(HeaderParam.class)) {
                HeaderParam ann = parameter.getAnnotation(HeaderParam.class);
                HeaderParameter headerParameter = new HeaderParameter();
                headerParameter.setName(ann.value());

                headerParameter.$ref(property.getType());

                headerParameter.setRequired(ann.required());
                if (parameter.getParameterizedType() instanceof Class && ((Class) parameter.getParameterizedType()).isEnum()) {
                    List<String> collect = Arrays.stream(((Class) parameter.getParameterizedType())
                            .getEnumConstants()).map(e -> e.toString())
                            .collect(Collectors.toList());
                    StringSchema stringSchema = new StringSchema();
                    stringSchema.setEnum(collect);
                    headerParameter.setSchema(stringSchema);
                }
                builder.add(headerParameter);
            }
        }

        return builder.build();
    }

    public String extractOperationMethod(ApiOperation apiOperation, Method method) {
        if (apiOperation.httpMethod() != null && !"".equals(apiOperation.httpMethod())) {
            return apiOperation.httpMethod().toLowerCase();
        } else if (method.getAnnotation(javax.ws.rs.GET.class) != null) {
            return "get";
        } else if (method.getAnnotation(javax.ws.rs.PUT.class) != null) {
            return "put";
        } else if (method.getAnnotation(javax.ws.rs.POST.class) != null) {
            return "post";
        } else if (method.getAnnotation(javax.ws.rs.DELETE.class) != null) {
            return "delete";
        } else if (method.getAnnotation(javax.ws.rs.OPTIONS.class) != null) {
            return "options";
        } else if (method.getAnnotation(javax.ws.rs.HEAD.class) != null) {
            return "patch";
        } else if (method.getAnnotation(HttpMethod.class) != null) {
            HttpMethod httpMethod = method.getAnnotation(HttpMethod.class);
            return httpMethod.value().toLowerCase();
        }
        return "post";
    }

    boolean isPrimitive(Type cls) {
        boolean out = false;

        Schema property = modelConverters.readAsProperty(new AnnotatedType().type(cls));
        if (property == null) {
            out = false;
        } else if ("integer".equals(property.getType())) {
            out = true;
        } else if ("string".equals(property.getType())) {
            out = true;
        } else if ("number".equals(property.getType())) {
            out = true;
        } else if ("boolean".equals(property.getType())) {
            out = true;
        } else if ("array".equals(property.getType())) {
            out = true;
        } else if ("file".equals(property.getType())) {
            out = true;
        }
        return out;
    }
}
