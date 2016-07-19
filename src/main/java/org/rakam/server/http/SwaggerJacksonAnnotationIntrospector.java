package org.rakam.server.http;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.rakam.server.http.annotations.ApiParam;

import javax.xml.bind.annotation.XmlElement;

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



    @Override
    public Boolean hasRequiredMarker(AnnotatedMember m) {
        ApiParam apiParam = m.getAnnotation(ApiParam.class);
        if(apiParam !=null) {
            return apiParam.required();
        }

        ApiModelProperty ann = m.getAnnotation(ApiModelProperty.class);
        if (ann != null) {
            return ann.required();
        }
        XmlElement elem = m.getAnnotation(XmlElement.class);
        if (elem != null) {
            if (elem.required()) {
                return true;
            }
        }
        return null;
    }

    @Override
    public String findPropertyDescription(Annotated a) {
        ApiModel model = a.getAnnotation(ApiModel.class);
        if (model != null && !"".equals(model.description())) {
            return model.description();
        }
        ApiModelProperty prop = a.getAnnotation(ApiModelProperty.class);
        if (prop != null) {
            return prop.value();
        }
        return null;
    }
}
