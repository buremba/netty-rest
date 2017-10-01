package org.rakam.server.http;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SHttpServer
        implements SHttpServerMBean
{
    private final HttpServer httpServer;

    public SHttpServer(HttpServer httpServer)
    {
        this.httpServer = httpServer;
    }

    public Map<String, String> getActiveRequests()
    {
        Map<String, Holder> builder = new HashMap<>();
        long l = System.currentTimeMillis();
        httpServer.processingRequests.entrySet().stream()
                .forEach(r -> builder.
                        compute(r.getKey().getMethod().name() + ":" + r.getKey().getUri(),
                                (k, v) -> (v == null ? new Holder(r.getValue()) : v.add(l - r.getValue()))));

        return builder.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().toString()));
    }

    public long getActiveClientCount()
    {
        return httpServer.activeChannels.size();
    }

    public static class Holder {
        public long count;
        public long sum;

        public Holder(Long first)
        {
            count = 1;
            sum = first;
        }

        public Holder add(long first) {
            count++;
            sum += first;
            return this;
        }

        @Override
        public String toString()
        {
            return "{" +
                    "count=" + count +
                    ", total duration=" + sum +
                    '}';
        }
    }
}
