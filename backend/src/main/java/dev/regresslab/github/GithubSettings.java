package dev.regresslab.github;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("regresslab.github-import")
public record GithubSettings(boolean enabled, @DefaultValue("classpath:github-demo-pipeline.json") String pipeline,
                             @DefaultValue("") String readToken) {}
