package dev.zenith.ppapi;

import java.util.UUID;

public class PPApiConfig {
    public boolean enabled = true;
    public int port = 8080;
    public String authToken = UUID.randomUUID().toString();
    public boolean rateLimiter = true;
    public int rateLimitRequestsPerMinute = 30;
    public boolean commandsAccountOwnerPerms = false;
}
