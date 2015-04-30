package org.rakam.server.http.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by buremba <Burak Emre Kabakcı> on 26/04/15 18:00.
 */
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

    int position() default 0;

    String nickname() default "";

    String produces() default "";

    String consumes() default "";

    String protocols() default "";

    Authorization[] authorizations() default {@Authorization(
            value = "",
            type = ""
    )};

    boolean hidden() default false;

    ResponseHeader[] responseHeaders() default {@ResponseHeader(
            name = "",
            response = Void.class
    )};
}
