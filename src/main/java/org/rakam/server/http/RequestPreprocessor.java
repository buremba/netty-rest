package org.rakam.server.http;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface RequestPreprocessor {
    void handle(RakamHttpRequest request, ObjectNode bodyData);
}
