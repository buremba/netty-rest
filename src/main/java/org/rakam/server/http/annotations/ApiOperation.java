package org.rakam.server.http.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiOperation {
    String value();

    String notes() default "";

    String[] tags() default {""};

    Class<?> response() default Void.class;

    Class<?> request() default Void.class;

    String responseContainer() default "";

    String httpMethod() default "";

    String nickname() default "";

    String produces() default "";

    String consumes() default "";

    String protocols() default "";

    Authorization[] authorizations() default {@Authorization(
            value = ""
    )};

    boolean hidden() default false;

    ResponseHeader[] responseHeaders() default {@ResponseHeader(
            name = "",
            response = Void.class
    )};
}
