package org.rakam.server.http;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import org.rakam.server.http.annotations.ApiParam;

public class SwaggerJacksonAnnotationIntrospector extends AnnotationIntrospector {
    @Override
    public Version version() {
        return null;
    }

    @Override
    public String findImplicitPropertyName(AnnotatedMember member) {
        ApiParam annotation = member.getAnnotation(ApiParam.class);
        if (annotation != null && annotation.value() != "") {
            return annotation.value();
        } else {
            return super.findImplicitPropertyName(member);
        }
    }
}
