package org.rakam.server.http;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.cookie.Cookie;

import java.lang.reflect.Type;
import java.util.function.Function;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;

public interface IRequestParameter<T>
{

    T extract(ObjectNode node, RakamHttpRequest request);

    class HeaderParameter<T>
            implements IRequestParameter
    {
        public final String name;
        public final boolean required;
        private final Function<String, T> mapper;

        HeaderParameter(String name, boolean required, Function<String, T> mapper)
        {
            this.name = name;
            this.required = required;
            this.mapper = mapper;
        }

        @Override
        public T extract(ObjectNode node, RakamHttpRequest request)
        {
            String value = request.headers().get(name);
            if (value == null && required) {
                throw new HttpRequestException("'" + name + "' header parameter is required.", BAD_REQUEST);
            }

            return mapper.apply(value);
        }
    }

    class CookieParameter
            implements IRequestParameter<String>
    {
        public final String name;
        public final boolean required;

        CookieParameter(String name, boolean required)
        {
            this.name = name;
            this.required = required;
        }

        @Override
        public String extract(ObjectNode node, RakamHttpRequest request)
        {
            for (Cookie cookie : request.cookies()) {
                if (name.equals(cookie.name())) {
                    // TODO fixme: the value of cookie parameter always must be String.
                    return cookie.value();
                }
            }
            return null;
        }
    }

    class BodyParameter
            implements IRequestParameter
    {
        public final String name;
        public final JavaType type;
        public final boolean required;
        private final ObjectMapper mapper;

        BodyParameter(ObjectMapper mapper, String name, Type type, boolean required)
        {
            this.name = name;
            this.type = mapper.constructType(type);
            this.mapper = mapper;
            this.required = required;
        }

        public Object extract(ObjectNode node, RakamHttpRequest request)
        {
            JsonNode value = node.get(name);
            Object o;
            if (value == null) {
                o = null;
            }
            else {
                try {
                    o = mapper.convertValue(value, type);
                }
                catch (IllegalArgumentException e) {
                    throw new HttpRequestException(name +
                            " body parameter cannot be cast to " + type.toString(), BAD_REQUEST);
                }
            }

            if (required && (o == null || o == NullNode.getInstance())) {
                throw new HttpRequestException(name + " body parameter is required", BAD_REQUEST);
            }

            return o;
        }
    }

    class FullBodyParameter
            implements IRequestParameter
    {
        public final JavaType type;
        private final ObjectMapper mapper;

        FullBodyParameter(ObjectMapper mapper, Type type)
        {
            this.type = mapper.constructType(type);
            this.mapper = mapper;
        }

        public Object extract(ObjectNode node, RakamHttpRequest request)
        {
            try {
                return mapper.convertValue(node, type);
            }
            catch (IllegalArgumentException e) {
                throw new HttpRequestException(e.getMessage(), BAD_REQUEST);
            }
        }
    }
}
