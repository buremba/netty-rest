package org.rakam.server.http;

import java.util.Map;

public interface SHttpServerMBean
{
    Map<String, Long> getActiveRequests();
    long getActiveClientCount();
}
