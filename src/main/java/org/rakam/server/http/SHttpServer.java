package org.rakam.server.http;

import java.util.HashMap;
import java.util.Map;

public class SHttpServer
        implements SHttpServerMBean
{
    private final HttpServer httpServer;

    public SHttpServer(HttpServer httpServer)
    {
        this.httpServer = httpServer;
    }

    public Map<String, Long> getActiveRequests()
    {
        Map<String, Long> builder = new HashMap<>();
        long l = System.currentTimeMillis();
        httpServer.processingRequests.entrySet().stream()
                .forEach(r -> builder.
                        compute(r.getKey().getMethod().name() + ":" + r.getKey().getUri(),
                                (k, v) -> l - (v == null ? r.getValue() : v + r.getValue())));
        return builder;
    }

    public long getActiveClientCount()
    {
        return httpServer.activeChannels.size();
    }
}
