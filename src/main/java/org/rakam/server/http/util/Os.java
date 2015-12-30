package org.rakam.server.http.util;

import io.netty.util.internal.SystemPropertyUtil;

import java.util.Locale;

public class Os {
    public static boolean supportsEpoll() {
        String name = SystemPropertyUtil.get("os.name").toLowerCase(Locale.UK).trim();
        return name.startsWith("linux");
    }
}
