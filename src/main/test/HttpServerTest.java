import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;
import org.rakam.server.http.HttpServer;
import org.rakam.server.http.HttpServerBuilder;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.BodyParam;
import org.rakam.server.http.annotations.JsonRequest;

import javax.inject.Named;
import javax.ws.rs.Path;

public class HttpServerTest {
    @Test
    public void testName() throws Exception {
        HttpServer build = new HttpServerBuilder().setHttpServices(ImmutableSet.of(new CustomHttpServer())).setCustomRequestParameters(ImmutableMap.of("test",
                method -> (node, request) -> request.getUri())).build();

        build.bindAwait("127.0.0.1", 7847);
    }

    @Path("/")
    public static class CustomHttpServer extends HttpService {
        @JsonRequest
        @ApiOperation(value = "Get user storage metadata", authorizations = @Authorization(value = "read_key"))
        @Path("/jsonbean")
        public String testJsonBean(@Named("test") String project, @BodyParam Demo demo) {
            return project;
        }

        public static class Demo {
            public final String test;

            @JsonCreator
            public Demo(@JsonProperty("test") String test) {
                this.test = test;
            }
        }
    }
}
