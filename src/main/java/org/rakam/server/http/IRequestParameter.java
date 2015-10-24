package org.rakam.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

import java.lang.reflect.Type;
import java.util.Set;

public interface IRequestParameter {

    <T> T extract(ObjectNode node, RakamHttpRequest request);

    boolean required();

    String name();

    String in();

    class HeaderParameter implements IRequestParameter {
        public final String name;
        public final boolean required;

        HeaderParameter(String name, boolean required) {
            this.name = name;
            this.required = required;
        }

        @Override
        public <T> T extract(ObjectNode node, RakamHttpRequest request) {
            return (T) request.headers().get(name);
        }

        @Override
        public boolean required() {
            return required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String in() {
            return "header";
        }
    }

    class CookieParameter implements IRequestParameter {
        public final String name;
        public final boolean required;

        CookieParameter(String name, boolean required) {
            this.name = name;
            this.required = required;
        }

        @Override
        public <T> T extract(ObjectNode node, RakamHttpRequest request) {
            // TODO: it's not acceptable to decode cookie for each parameter.
            // move the logic to RakamHttpRequest.
            final Set<Cookie> cookies = ServerCookieDecoder.STRICT
                    .decode(request.headers().get(HttpHeaders.Names.COOKIE));
            for (Cookie cookie : cookies) {
                if(name.equals(cookie.name())) {
                    // TODO fixme: the value of cookie parameter always must be String.
                    return (T) cookie.value();
                }
            }
            return null;
        }

        @Override
        public boolean required() {
            return required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String in() {
            return "header";
        }
    }

    class BodyParameter implements IRequestParameter {
        public final String name;
        public final Type type;
        public final boolean required;
        private final ObjectMapper mapper;

        BodyParameter(ObjectMapper mapper, String name, Type type, boolean required) {
            this.name = name;
            this.type = type;
            this.mapper = mapper;
            this.required = required;
        }

        public <T> T extract(ObjectNode node, RakamHttpRequest request) {
            JsonNode value = node.get(name);
            if (value == null) {
                return null;
            }
            return mapper.convertValue(value, mapper.constructType(type));
        }

        @Override
        public boolean required() {
            return required;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String in() {
            return "body";
        }
    }
}
