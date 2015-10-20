package org.rakam.server.http;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.converter.ModelConverter;
import io.swagger.converter.ModelConverterContextImpl;
import io.swagger.jackson.ModelResolver;
import io.swagger.models.Model;
import io.swagger.models.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModelConverters {
    static Logger LOGGER = LoggerFactory.getLogger(ModelConverters.class);
    private final List<ModelConverter> converters;
    private final Set<String> skippedPackages = new HashSet<String>();
    private final Set<String> skippedClasses = new HashSet<String>();

    public ModelConverters(ObjectMapper mapper) {
        AnnotationIntrospector annotationIntrospector = mapper.getDeserializationConfig().getAnnotationIntrospector();

        converters = new CopyOnWriteArrayList<>();
        ModelResolver e = new ModelResolver(mapper);
        converters.add(e);
    }

    public void addConverter(ModelConverter converter) {
        converters.add(0, converter);
    }

    public void removeConverter(ModelConverter converter) {
        converters.remove(converter);
    }

    public void addPackageToSkip(String pkg) {
        this.skippedPackages.add(pkg);
    }

    public void addClassToSkip(String cls) {
        LOGGER.warn("skipping class " + cls);
        this.skippedClasses.add(cls);
    }

    public Property readAsProperty(Type type) {
        ModelConverterContextImpl context = new ModelConverterContextImpl(
                converters);
        return context.resolveProperty(type, null);
    }

    public Map<String, Model> read(Type type) {
        Map<String, Model> modelMap = new HashMap<String, Model>();
        if (shouldProcess(type)) {
            ModelConverterContextImpl context = new ModelConverterContextImpl(
                    converters);
            Model resolve = context.resolve(type);
            context.getDefinedModels()
                    .entrySet().stream().filter(entry -> entry.getValue().equals(resolve))
                    .forEach(entry -> modelMap.put(entry.getKey(), entry.getValue()));
        }
        return modelMap;
    }

    public Map<String, Model> readAll(Type type) {
        if (shouldProcess(type)) {
            ModelConverterContextImpl context = new ModelConverterContextImpl(
                    converters);

            LOGGER.debug("ModelConverters readAll from " + type);
            context.resolve(type);
            return context.getDefinedModels();
        }
        return new HashMap<String, Model>();
    }

    private boolean shouldProcess(Type type) {
        final Class<?> cls = TypeFactory.defaultInstance().constructType(type).getRawClass();
        if (cls.isPrimitive()) {
            return false;
        }
        String className = cls.getName();
        for (String packageName : skippedPackages) {
            if (className.startsWith(packageName)) {
                return false;
            }
        }
        for (String classToSkip : skippedClasses) {
            if (className.equals(classToSkip)) {
                return false;
            }
        }
        return true;
    }
}