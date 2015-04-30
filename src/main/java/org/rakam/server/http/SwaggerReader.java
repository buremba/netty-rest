package org.rakam.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.AuthorizationScope;
import com.wordnik.swagger.converter.ModelConverters;
import com.wordnik.swagger.models.ArrayModel;
import com.wordnik.swagger.models.Model;
import com.wordnik.swagger.models.ModelImpl;
import com.wordnik.swagger.models.Operation;
import com.wordnik.swagger.models.Path;
import com.wordnik.swagger.models.RefModel;
import com.wordnik.swagger.models.Response;
import com.wordnik.swagger.models.Scheme;
import com.wordnik.swagger.models.SecurityDefinition;
import com.wordnik.swagger.models.SecurityRequirement;
import com.wordnik.swagger.models.SecurityScope;
import com.wordnik.swagger.models.Swagger;
import com.wordnik.swagger.models.Tag;
import com.wordnik.swagger.models.parameters.BodyParameter;
import com.wordnik.swagger.models.parameters.Parameter;
import com.wordnik.swagger.models.parameters.QueryParameter;
import com.wordnik.swagger.models.parameters.SerializableParameter;
import com.wordnik.swagger.models.properties.ArrayProperty;
import com.wordnik.swagger.models.properties.MapProperty;
import com.wordnik.swagger.models.properties.Property;
import com.wordnik.swagger.models.properties.RefProperty;
import com.wordnik.swagger.util.Json;
import org.rakam.server.http.annotations.ApiParamIgnore;
import org.rakam.server.http.annotations.ParamBody;
import org.rakam.server.http.annotations.ResponseHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Produces;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Created by buremba <Burak Emre Kabakcı> on 24/04/15 22:44.
 */
public class SwaggerReader {
    Logger LOGGER = LoggerFactory.getLogger(SwaggerReader.class);

    Swagger swagger;
    static ObjectMapper m = Json.mapper();

    public SwaggerReader(Swagger swagger) {
        this.swagger = swagger;
    }

    public Swagger read(Class cls) {
        return read(cls, "", false, new HashMap<>(), new ArrayList<>());
    }

    protected Swagger read(Class<?> cls, String parentPath, boolean readHidden, Map<String, Tag> parentTags, List<Parameter> parentParameters) {
        if(swagger == null)
            swagger = new Swagger();
        Api api = cls.getAnnotation(Api.class);
        Map<String, SecurityScope> globalScopes = new HashMap<>();

        javax.ws.rs.Path apiPath = cls.getAnnotation(javax.ws.rs.Path.class);

        // only read if allowing hidden apis OR api is not marked as hidden
        if((api != null && readHidden) || (api != null && !api.hidden())) {
            // the value will be used as a tag for 2.0 UNLESS a Tags annotation is present
            Set<String> tagStrings = extractTags(api);
            Map<String, Tag> tags = new HashMap<>();
            for(String tagString : tagStrings) {
                Tag tag = new Tag().name(tagString);
                tags.put(tagString, tag);
            }
            if(parentTags != null)
                tags.putAll(parentTags);
            for(String tagName: tags.keySet()) {
                swagger.tag(tags.get(tagName));
            }

            Authorization[] authorizations = api.authorizations();

            List<SecurityRequirement> securities = new ArrayList<>();
            for(Authorization auth : authorizations) {
                if(auth.value() != null && !"".equals(auth.value())) {
                    SecurityRequirement security = new SecurityRequirement();
                    security.setName(auth.value());
                    AuthorizationScope[] scopes = auth.scopes();
                    for(AuthorizationScope scope : scopes) {
                        if(scope.scope() != null && !"".equals(scope.scope())) {
                            security.addScope(scope.scope());
                        }
                    }
                    securities.add(security);
                }
            }

            // parse the method
            Method methods[] = cls.getMethods();
            for(Method method : methods) {
                ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
                javax.ws.rs.Path methodPath = method.getAnnotation(javax.ws.rs.Path.class);

                String operationPath = getPath(apiPath, methodPath, parentPath);
                if(operationPath != null && apiOperation != null) {
                    String [] pps = operationPath.split("/");
                    String [] pathParts = new String[pps.length];
                    Map<String, String> regexMap = new HashMap<>();

                    for(int i = 0; i < pps.length; i++) {
                        String p = pps[i];
                        if(p.startsWith("{")) {
                            int pos = p.indexOf(":");
                            if(pos > 0) {
                                String left = p.substring(1, pos);
                                String right = p.substring(pos + 1, p.length()-1);
                                pathParts[i] = "{" + left + "}";
                                regexMap.put(left, right);
                            }
                            else
                                pathParts[i] = p;
                        }
                        else pathParts[i] = p;
                    }
                    StringBuilder pathBuilder = new StringBuilder();
                    for(String p : pathParts) {
                        if(!p.isEmpty())
                            pathBuilder.append("/").append(p);
                    }
                    operationPath = pathBuilder.toString();

                    String httpMethod = extractOperationMethod(apiOperation, method);

                    Operation operation = parseMethod(method);
                    if(parentParameters != null) {
                        parentParameters.forEach(operation::parameter);
                    }
                    operation.getParameters().stream().filter(param -> regexMap.get(param.getName()) != null).forEach(param -> {
                        String pattern = regexMap.get(param.getName());
                        param.setPattern(pattern);
                    });

                    String protocols = apiOperation.protocols();
                    if(!"".equals(protocols)) {
                        String[] parts = protocols.split(",");
                        for(String part : parts) {
                            String trimmed = part.trim();
                            if(!"".equals(trimmed))
                                operation.scheme(Scheme.forValue(trimmed));
                        }
                    }

                    if(isSubResource(method)) {
                        Class<?> responseClass = method.getReturnType();
                        read(responseClass, operationPath, true, tags, operation.getParameters());
                    }

                    // can't continue without a valid http method
                    if(httpMethod != null) {
                        ApiOperation op = method.getAnnotation(ApiOperation.class);
                        if(op != null) {
                            for(String tag : op.tags()) {
                                if(!"".equals(tag)) {
                                    operation.tag(tag);
                                    swagger.tag(new Tag().name(tag));
                                }
                            }
                        }
                        if(operation != null) {
                            Consumes consumes = method.getAnnotation(Consumes.class);
                            if(consumes != null)  {
                                operation.consumes(Arrays.asList(consumes.value()));
                            }else {
                                operation.consumes("application/json");
                            }

                            Produces produces = method.getAnnotation(Produces.class);
                            if(produces != null)  {
                                operation.produces(Arrays.asList(produces.value()));
                            }else {
                                operation.produces("application/json");
                            }

                            if(operation.getTags() == null) {
                                tags.keySet().forEach(operation::tag);
                            }
                            securities.forEach(operation::security);

                            Path path = swagger.getPath(operationPath);
                            if(path == null) {
                                path = new Path();
                                swagger.path(operationPath, path);
                            }
                            path.set(httpMethod, operation);
                        }
                    }
                }
            }
        }
        return swagger;
    }

    protected boolean isSubResource(Method method) {
        Type t = method.getGenericReturnType();
        Class<?> responseClass = method.getReturnType();
        if(responseClass != null && responseClass.getAnnotation(Api.class) != null) {
            return true;
        }
        return false;
    }

    protected Set<String> extractTags(Api api) {
        Set<String> output = new LinkedHashSet<>();

        boolean hasExplicitTags = false;
        for(String tag : api.tags()) {
            if(!"".equals(tag)) {
                hasExplicitTags = true;
                output.add(tag);
            }
        }
        if(!hasExplicitTags) {
            // derive tag from api path + description
            String tagString = api.value().replace("/", "");
            if(!"".equals(tagString))
                output.add(tagString);
        }
        return output;
    }

    String getPath(javax.ws.rs.Path classLevelPath, javax.ws.rs.Path methodLevelPath, String parentPath) {
        if(classLevelPath == null && methodLevelPath == null)
            return null;
        StringBuilder b = new StringBuilder();
        if(parentPath != null && !"".equals(parentPath) && !"/".equals(parentPath)) {
            if(!parentPath.startsWith("/"))
                parentPath = "/" + parentPath;
            if(parentPath.endsWith("/"))
                parentPath = parentPath.substring(0, parentPath.length() - 1);

            b.append(parentPath);
        }
        if(classLevelPath != null) {
            b.append(classLevelPath.value());
        }
        if(methodLevelPath != null && !"/".equals(methodLevelPath.value())) {
            String methodPath = methodLevelPath.value();
            if(!methodPath.startsWith("/") && !b.toString().endsWith("/")) {
                b.append("/");
            }
            if(methodPath.endsWith("/")) {
                methodPath = methodPath.substring(0, methodPath.length() -1);
            }
            b.append(methodPath);
        }
        String output = b.toString();
        if(!output.startsWith("/"))
            output = "/" + output;
        if(output.endsWith("/") && output.length() > 1)
            return output.substring(0, output.length() - 1);
        else
            return output;
    }

    public Map<String, Property> parseResponseHeaders(ResponseHeader[] headers) {
        Map<String,Property> responseHeaders = null;
        if(headers != null && headers.length > 0) {
            for(ResponseHeader header : headers) {
                String name = header.name();
                if(!"".equals(name)) {
                    if(responseHeaders == null)
                        responseHeaders = new HashMap<String, Property>();
                    String description = header.description();
                    Class<?> cls = header.response();
                    String container = header.responseContainer();

                    if(!cls.equals(java.lang.Void.class) && !"void".equals(cls.toString())) {
                        Property responseProperty = null;
                        Property property = ModelConverters.getInstance().readAsProperty(cls);
                        if(property != null) {
                            if("list".equalsIgnoreCase(container))
                                responseProperty = new ArrayProperty(property);
                            else if("map".equalsIgnoreCase(container))
                                responseProperty = new MapProperty(property);
                            else
                                responseProperty = property;
                            responseProperty.setDescription(description);
                            responseHeaders.put(name, responseProperty);
                        }
                    }
                }
            }
        }
        return responseHeaders;
    }

    public Operation parseMethod(Method method) {
        Operation operation = new Operation();

        ApiOperation apiOperation = method.getAnnotation(ApiOperation.class);
        ApiResponses responseAnnotation = method.getAnnotation(ApiResponses.class);

        String operationId = method.getName();
        String responseContainer = null;

        Class<?> responseClass = null;
        Map<String,Property> defaultResponseHeaders = new HashMap<String, Property>();

        if(apiOperation != null) {
            if(apiOperation.hidden())
                return null;
            if(!"".equals(apiOperation.nickname()))
                operationId = method.getName();

            defaultResponseHeaders = parseResponseHeaders(apiOperation.responseHeaders());

            operation
                    .summary(apiOperation.value())
                    .description(apiOperation.notes());

            if(apiOperation.response() != null && !Void.class.equals(apiOperation.response()))
                responseClass = apiOperation.response();
            if(!"".equals(apiOperation.responseContainer()))
                responseContainer = apiOperation.responseContainer();
            if(apiOperation.authorizations()!= null) {
                List<SecurityRequirement> securities = new ArrayList<>();
                for(Authorization auth : apiOperation.authorizations()) {
                    if(auth.value() != null && !"".equals(auth.value())) {
                        SecurityRequirement security = new SecurityRequirement();
                        security.setName(auth.value());
                        AuthorizationScope[] scopes = auth.scopes();
                        for(AuthorizationScope scope : scopes) {
                            SecurityDefinition definition = new SecurityDefinition(auth.type());
                            if(scope.scope() != null && !"".equals(scope.scope())) {
                                security.addScope(scope.scope());
                                definition.scope(scope.scope(), scope.description());
                            }
                        }
                        securities.add(security);
                    }
                }
                securities.forEach(operation::security);
            }
        }

        if(responseClass == null) {
            // pick out response from method declaration
            LOGGER.debug("picking up response class from method " + method);
            Type t = method.getGenericReturnType();
            responseClass = method.getReturnType();
            if(!responseClass.equals(java.lang.Void.class) && !"void".equals(responseClass.toString()) && responseClass.getAnnotation(Api.class) == null) {
                LOGGER.debug("reading model " + responseClass);
                Map<String, Model> models = ModelConverters.getInstance().readAll(t);
            }
        }
        if(responseClass != null
                && !responseClass.equals(java.lang.Void.class)
                && !responseClass.equals(javax.ws.rs.core.Response.class)
                && responseClass.getAnnotation(Api.class) == null) {
            if(isPrimitive(responseClass)) {
                Property responseProperty;
                Property property = ModelConverters.getInstance().readAsProperty(responseClass);
                if(property != null) {
                    if("list".equalsIgnoreCase(responseContainer))
                        responseProperty = new ArrayProperty(property);
                    else if("map".equalsIgnoreCase(responseContainer))
                        responseProperty = new MapProperty(property);
                    else
                        responseProperty = property;
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                }
            }
            else if(!responseClass.equals(java.lang.Void.class) && !"void".equals(responseClass.toString())) {
                Map<String, Model> models = ModelConverters.getInstance().read(responseClass);
                if(models.size() == 0) {
                    Property p = ModelConverters.getInstance().readAsProperty(responseClass);
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(p)
                            .headers(defaultResponseHeaders));
                }
                for(String key: models.keySet()) {
                    Property responseProperty;

                    if("list".equalsIgnoreCase(responseContainer))
                        responseProperty = new ArrayProperty(new RefProperty().asDefault(key));
                    else if("map".equalsIgnoreCase(responseContainer))
                        responseProperty = new MapProperty(new RefProperty().asDefault(key));
                    else
                        responseProperty = new RefProperty().asDefault(key);
                    operation.response(200, new Response()
                            .description("successful operation")
                            .schema(responseProperty)
                            .headers(defaultResponseHeaders));
                    swagger.model(key, models.get(key));
                }
                models = ModelConverters.getInstance().readAll(responseClass);
                for(String key: models.keySet()) {
                    swagger.model(key, models.get(key));
                }
            }
        }

        operation.operationId(operationId);

        Annotation annotation;
        annotation = method.getAnnotation(Consumes.class);
        if(annotation != null) {
            String[] apiConsumes = ((Consumes)annotation).value();
            for(String mediaType: apiConsumes)
                operation.consumes(mediaType);
        }

        annotation = method.getAnnotation(Produces.class);
        if(annotation != null) {
            String[] apiProduces = ((Produces)annotation).value();
            for(String mediaType: apiProduces)
                operation.produces(mediaType);
        }

        List<ApiResponse> apiResponses = new ArrayList<>();
        if(responseAnnotation != null) {
            for(ApiResponse apiResponse: responseAnnotation.value()) {
                Map<String,Property> responseHeaders = parseResponseHeaders(apiResponse.responseHeaders());

                Response response = new Response()
                        .description(apiResponse.message())
                        .headers(responseHeaders);

                if(apiResponse.code() == 0)
                    operation.defaultResponse(response);
                else
                    operation.response(apiResponse.code(), response);

                responseClass = apiResponse.response();
                if(responseClass != null && !responseClass.equals(java.lang.Void.class)) {
                    Map<String, Model> models = ModelConverters.getInstance().read(responseClass);
                    for(String key: models.keySet()) {
                        response.schema(new RefProperty().asDefault(key));
                        swagger.model(key, models.get(key));
                    }
                    models = ModelConverters.getInstance().readAll(responseClass);
                    for(String key: models.keySet()) {
                        swagger.model(key, models.get(key));
                    }
                }
            }
        }
        boolean isDeprecated = false;
        annotation = method.getAnnotation(Deprecated.class);
        if(annotation != null)
            isDeprecated = true;

        Class[] parameterTypes = method.getParameterTypes();
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();

        if(method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class)!=null) {
            Field[] fields = method.getParameters()[0].getType().getFields();
            for (Field field : fields) {
                if(field.isAnnotationPresent(ApiParam.class)) {
                    getParameters(field.getClass(), field.getType(), field.getAnnotations()).forEach(operation::parameter);
                }else
                if(!field.isAnnotationPresent(ApiParamIgnore.class)){
                    QueryParameter parameter = new QueryParameter();
                    parameter.name(field.getName());

                    Property schema = ModelConverters.getInstance().readAsProperty(field.getType());
                    if (schema != null) {
                        parameter.setProperty(schema);
                        if (schema instanceof ArrayProperty) {
                            parameter.setItems(((ArrayProperty) schema).getItems());
                        }
                    }

                    operation.parameter(parameter);
                }
            }
        }else {
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> cls = parameterTypes[i];
                Type type = genericParameterTypes[i];
                List<Parameter> parameters = getParameters(cls, type, paramAnnotations[i]);
                parameters.forEach(operation::parameter);
            }
        }
        if(operation.getResponses() == null) {
            operation.defaultResponse(new Response().description("successful operation"));
        }
        return operation;
    }

    List<Parameter> getParameters(Class<?> cls, Type type, Annotation[] annotations) {
        // look for path, query
        boolean isArray = isMethodArgumentAnArray(cls, type);
        List<Parameter> parameters;

        LOGGER.debug("getParameters for " + cls);
        Set<Class<?>> classesToSkip = new HashSet<>();
        parameters = extractParameters(annotations, type);

        if(parameters.size() > 0) {
            for(Parameter parameter : parameters) {
                applyAnnotations(swagger, parameter, cls, annotations, isArray);
            }
        }
        else {
            LOGGER.debug("no parameter found, looking at body params");
            if(classesToSkip.contains(cls) == false) {
                if(type instanceof ParameterizedType) {
                    ParameterizedType ti = (ParameterizedType) type;
                    Type innerType = ti.getActualTypeArguments()[0];
                    if(innerType instanceof Class) {
                        Parameter param = applyAnnotations(swagger, null, (Class) innerType, annotations, isArray);
                        if(param != null) {
                            parameters.add(param);
                        }
                    }
                }
                else {
                    Parameter param = applyAnnotations(swagger, null, cls, annotations, isArray);
                    if(param != null) {
                        parameters.add(param);
                    }
                }
            }
        }
        return parameters;
    }

    public String extractOperationMethod(ApiOperation apiOperation, Method method) {
        if(apiOperation.httpMethod() != null && !"".equals(apiOperation.httpMethod()))
            return apiOperation.httpMethod().toLowerCase();
        else if(method.getAnnotation(javax.ws.rs.GET.class) != null)
            return "get";
        else if(method.getAnnotation(javax.ws.rs.PUT.class) != null)
            return "put";
        else if(method.getAnnotation(javax.ws.rs.POST.class) != null)
            return "post";
        else if(method.getAnnotation(javax.ws.rs.DELETE.class) != null)
            return "delete";
        else if(method.getAnnotation(javax.ws.rs.OPTIONS.class) != null)
            return "options";
        else if(method.getAnnotation(javax.ws.rs.HEAD.class) != null)
            return "patch";
        else if(method.getAnnotation(HttpMethod.class) != null) {
            HttpMethod httpMethod = method.getAnnotation(HttpMethod.class);
            return httpMethod.value().toLowerCase();
        }
        return "post";
    }

    boolean isPrimitive(Class<?> cls) {
        boolean out = false;

        Property property = ModelConverters.getInstance().readAsProperty(cls);
        if(property == null)
            out = false;
        else if("integer".equals(property.getType()))
            out = true;
        else if("string".equals(property.getType()))
            out = true;
        else if("number".equals(property.getType()))
            out = true;
        else if("boolean".equals(property.getType()))
            out = true;
        else if("array".equals(property.getType()))
            out = true;
        else if("file".equals(property.getType()))
            out = true;
        return out;
    }

    public Parameter applyAnnotations(Swagger swagger, Parameter parameter, Class<?> cls, Annotation[] annotations, boolean isArray) {
        boolean shouldIgnore = false;
        String allowableValues;

        Optional<Annotation> any = Arrays.stream(annotations).filter(ann -> ann instanceof ApiParam).findAny();
        if(any.isPresent()) {
            ApiParam param = (ApiParam) any.get();

            if(parameter != null) {
                parameter.setRequired(param.required());
                if(param.name() != null && !"".equals(param.name()))
                    parameter.setName(param.name());
                parameter.setDescription(param.value());
                parameter.setAccess(param.access());
                allowableValues = param.allowableValues();
                if(allowableValues != null) {
                    if (allowableValues.startsWith("range")) {
                        // TODO handle range
                    }
                    else {
                        String[] values = allowableValues.split(",");
                        List<String> _enum = new ArrayList<>();
                        for(String value : values) {
                            String trimmed = value.trim();
                            if(!trimmed.equals(""))
                                _enum.add(trimmed);
                        }
                        if(parameter instanceof SerializableParameter) {
                            SerializableParameter p = (SerializableParameter) parameter;
                            if(_enum.size() > 0)
                                p.setEnum(_enum);
                        }
                    }
                }
            }
            else if(shouldIgnore == false) {
                // must be a body param
                BodyParameter bp = new BodyParameter();
                if(param.name() != null && !"".equals(param.name()))
                    bp.setName(param.name());
                else
                    bp.setName("body");
                bp.setDescription(param.value());

                if(cls.isArray() || isArray) {
                    Class<?> innerType;
                    if(isArray) {// array has already been detected
                        innerType = cls;
                    }
                    else
                        innerType = cls.getComponentType();
                    LOGGER.debug("inner type: " + innerType + " from " + cls);
                    Property innerProperty = ModelConverters.getInstance().readAsProperty(innerType);
                    if(innerProperty == null) {
                        Map<String, Model> models = ModelConverters.getInstance().read(innerType);
                        if(models.size() > 0) {
                            for(String name: models.keySet()) {
                                if(name.indexOf("java.util") == -1) {
                                    bp.setSchema(
                                            new ArrayModel().items(new RefProperty().asDefault(name)));
                                    if(swagger != null)
                                        swagger.addDefinition(name, models.get(name));
                                }
                            }
                        }
                        models = ModelConverters.getInstance().readAll(innerType);
                        if(swagger != null) {
                            for(String key : models.keySet()) {
                                swagger.model(key, models.get(key));
                            }
                        }
                    }
                    else {
                        LOGGER.debug("found inner property " + innerProperty);
                        bp.setSchema(new ArrayModel().items(innerProperty));

                        // creation of ref property doesn't add model to definitions - do it now instead
                        if( innerProperty instanceof RefProperty && swagger != null) {
                            Map<String, Model> models = ModelConverters.getInstance().read(innerType);
                            String name = ((RefProperty)innerProperty).getSimpleRef();
                            swagger.addDefinition(name, models.get(name));

                            LOGGER.debug( "added model definition for RefProperty " + name );
                        }
                    }

                }
                else {
                    Map<String, Model> models = ModelConverters.getInstance().read(cls);
                    if(models.size() > 0) {
                        for(String name: models.keySet()) {
                            if(name.indexOf("java.util") == -1) {
                                if(isArray)
                                    bp.setSchema(new ArrayModel().items(new RefProperty().asDefault(name)));
                                else
                                    bp.setSchema(new RefModel().asDefault(name));
                                if(swagger != null)
                                    swagger.addDefinition(name, models.get(name));
                            }
                        }
                        models = ModelConverters.getInstance().readAll(cls);
                        if(swagger != null) {
                            for(String key : models.keySet()) {
                                swagger.model(key, models.get(key));
                            }
                        }
                    }
                    else {
                        Property prop = ModelConverters.getInstance().readAsProperty(cls);
                        if(prop != null) {
                            ModelImpl model = new ModelImpl();
                            model.setType(prop.getType());
                            bp.setSchema(model);
                        }
                    }
                }
                parameter = bp;
            }
        }
        return parameter;
    }

    public List<Parameter> extractParameters(Annotation[] annotations, Type type) {
        String defaultValue = null;

        List<Parameter> parameters = new ArrayList<>();
        Parameter parameter = null;

        for(Annotation annotation : annotations) {
            if(annotation instanceof ApiParam) {
                QueryParameter qp = new QueryParameter()
                        .name(((ApiParam) annotation).name());
                qp.setDefaultValue(defaultValue);
                Property schema = ModelConverters.getInstance().readAsProperty(type);
                if (schema != null) {
                    qp.setProperty(schema);
                    if (schema instanceof ArrayProperty) {
                        qp.setItems(((ArrayProperty) schema).getItems());
                    }
                }
                parameter = qp;
            }
        }
        if(parameter != null) {
            parameters.add(parameter);
        }

        return parameters;
    }

    public static boolean isMethodArgumentAnArray(final Class<?> paramClass, final Type paramGenericType) {
        final Class<?>[] interfaces = paramClass.getInterfaces();
        boolean isArray = false;

        for (final Class<?> aCls : interfaces) {
            if (List.class.equals(aCls)) {
                isArray = true;

                break;
            }
        }

        if (paramGenericType instanceof ParameterizedType){
            final Type[] parameterArgTypes = ((ParameterizedType)paramGenericType).getActualTypeArguments();
            Class<?> testClass = paramClass;

            for (Type parameterArgType : parameterArgTypes){
                if (testClass.isAssignableFrom(List.class)){
                    isArray = true;

                    break;
                }

                testClass = (Class<?>) parameterArgType;
            }
        }

        return isArray;
    }
}
