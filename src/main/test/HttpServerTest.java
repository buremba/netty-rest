import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;
import org.rakam.server.http.HttpServer;
import org.rakam.server.http.HttpServerBuilder;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
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
//        @GET
//        @ApiOperation(value = "Get user storage metadata", authorizations = @Authorization(value = "read_key"))
//        @ApiResponses(value = {
//                @ApiResponse(code = 400, message = "Project does not exist.")})
//        @Path("/metadata")
//        public String test(@Named("test") String project) {
//            return project;
//        }
//
//        @GET
//        @ApiOperation(value = "Get user storage metadata", authorizations = @Authorization(value = "read_key"))
//        @ApiResponses(value = {
//                @ApiResponse(code = 400, message = "Project does not exist.")})
//        @Path("/raw")
//        public String raw(RakamHttpRequest request, @Named("test") String project) {
//            return project;
//        }
//
//        @JsonRequest
//        @ApiOperation(value = "Get user storage metadata", authorizations = @Authorization(value = "read_key"))
//        @ApiResponses(value = {
//                @ApiResponse(code = 400, message = "Project does not exist.")})
//        @Path("/json")
//        public String testJson(@Named("test") String project, @ApiParam("test") String test) {
//            return project;
//        }

        @JsonRequest
        @ApiOperation(value = "Get user storage metadata", authorizations = @Authorization(value = "read_key"))
        @ApiResponses(value = {
                @ApiResponse(code = 400, message = "Project does not exist.")})
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
