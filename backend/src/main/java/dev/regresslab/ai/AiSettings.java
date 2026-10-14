package dev.regresslab.ai;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("regresslab.ai")
public record AiSettings(boolean enabled, @DefaultValue("http://localhost:11434") String ollamaUrl,
                         @DefaultValue("qwen3:4b") String model, @DefaultValue("120") int timeoutSeconds, @DefaultValue("16384") int contextTokens) {
    public AiSettings(boolean enabled, String url, String model, int timeout) { this(enabled,url,model,timeout,16384); }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AiSettings {
        URI uri;
        try { uri = URI.create(ollamaUrl); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid local Ollama URL"); }
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null
                || !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")) || uri.getPort() < 1 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Ollama must use a loopback HTTP origin with an explicit port");
        if (model == null || !model.matches("[A-Za-z0-9_.:/-]{1,100}") || timeoutSeconds < 1 || timeoutSeconds > 300 || contextTokens < 8192 || contextTokens > 16384
                || model.endsWith("-cloud") || model.endsWith(":cloud"))
            throw new IllegalArgumentException("Invalid local model, timeout (1-300 seconds) or context (8192-16384 tokens)");
    }
}
