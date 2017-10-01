package org.rakam.server.http;

import java.util.Map;

public interface SHttpServerMBean
{
    Map<String, String> getActiveRequests();
    long getActiveClientCount();
}
