package dev.regresslab.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("regresslab.local-ingestion")
public record LocalIngestionSettings(boolean enabled, String token, long repositoryGithubId, String repositoryFullName,
                                     @DefaultValue("30") int requestsPerMinute) {}
