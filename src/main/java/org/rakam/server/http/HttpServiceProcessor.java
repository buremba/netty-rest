package org.rakam.server.http;

import org.rakam.server.http.annotations.JsonRequest;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import javax.ws.rs.Path;
import java.util.Set;

import static java.lang.String.format;

/**
 * Created by buremba <Burak Emre Kabakcı> on 31/03/15 06:26.
 */
@SupportedAnnotationTypes(
        {"org.rakam.server.Service", "org.rakam.server.annotations.JsonRequest"}
)
public class HttpServiceProcessor extends AbstractProcessor {
    @Override
    public boolean process(Set<? extends TypeElement> typeElements, RoundEnvironment roundEnvironment) {
        final ProcessingEnvironment env = super.processingEnv;
        Messager messager = env.getMessager();

        Set<? extends Element> elements = roundEnvironment.getElementsAnnotatedWith(Service.class);
        for (Element element : elements) {
            Path annotation = element.getAnnotation(Path.class);
            if(annotation == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, format("%s class extends HttpService but doesn't have javax.ws.rs.Path annotation. " +
                        "HttpService subclasses must have it in order to define their scope.", element.toString()));
            }
        }

        Set<? extends Element> jsonMethods = roundEnvironment.getElementsAnnotatedWith(JsonRequest.class);
        for (Element element : jsonMethods) {
            Path annotation = element.getAnnotation(Path.class);
            if(annotation == null) {
                messager.printMessage(Diagnostic.Kind.ERROR, format("%s class has %s but %s annotation but doesn't have %s annotation",
                        element.toString(),
                        JsonRequest.class.getCanonicalName(),
                        Path.class.getCanonicalName()));
            }

            // we know that JsonRequest can only be used in method declarations so the Type must be ExecutableElement
            if(((ExecutableElement) element.asType()).getReturnType().getKind() == TypeKind.VOID) {
                messager.printMessage(Diagnostic.Kind.ERROR, format("%s method can't have void return type because it has %s annotation",
                        element.toString(),
                        JsonRequest.class.getCanonicalName()));
            }
            if(((ExecutableElement) element.asType()).getParameters().size() != 1) {
                messager.printMessage(Diagnostic.Kind.ERROR, format("%s method must have exactly one parameter because it has %s annotation",
                        element.toString(),
                        JsonRequest.class.getCanonicalName()));
            }
        }

        return true;
    }



    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }
}
