package org.rakam.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ModelConverters {
    static Logger LOGGER = LoggerFactory.getLogger(ModelConverters.class);
    private final List<ModelConverter> converters;
    private final Set<String> skippedPackages = new HashSet<String>();
    private final Set<String> skippedClasses = new HashSet<String>();

    public ModelConverters(ObjectMapper mapper) {
        converters = new CopyOnWriteArrayList<>();
        ModelResolver e = new ModelResolver(mapper);
        converters.add(e);
    }

    public void addConverter(io.swagger.v3.core.converter.ModelConverter converter) {
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

    public Schema readAsProperty(AnnotatedType type) {
        ModelConverterContextImpl context = new ModelConverterContextImpl(converters);
        return context.resolve(type);
    }

    public Map<String, Schema> read(AnnotatedType type) {
        Map<String, Schema> modelMap = new HashMap();
        if (shouldProcess(type)) {
            ModelConverterContextImpl context = new ModelConverterContextImpl(
                    converters);
            Schema resolve = context.resolve(type);
            context.getDefinedModels()
                    .entrySet().stream().filter(entry -> entry.getValue().equals(resolve))
                    .forEach(entry -> modelMap.put(entry.getKey(), entry.getValue()));
        }
        return modelMap;
    }

    public Map<String, Schema> readAll(AnnotatedType type) {
        if (shouldProcess(type)) {
            ModelConverterContextImpl context = new ModelConverterContextImpl(
                    converters);

            LOGGER.debug("ModelConverters readAll from " + type);
            context.resolve(type);
            return context.getDefinedModels();
        }
        return new HashMap<>();
    }

    private boolean shouldProcess(AnnotatedType type) {
        final Class<?> cls = TypeFactory.defaultInstance().constructType(type.getType()).getRawClass();
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