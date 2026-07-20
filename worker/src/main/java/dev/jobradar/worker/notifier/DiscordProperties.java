package dev.jobradar.worker.notifier;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "worker.discord")
public record DiscordProperties(String webhookUrl) {
}
