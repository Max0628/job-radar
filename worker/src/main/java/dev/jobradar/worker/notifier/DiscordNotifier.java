package dev.jobradar.worker.notifier;

import dev.jobradar.common.envelope.EventType;
import dev.jobradar.common.envelope.JobEventEnvelope;
import dev.jobradar.common.kafka.Topics;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * jobs.events 消費者：只處理 NEW 事件（見 proposal.md non-goals，CHANGED 尚未發送）。
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

    private final RestClient restClient;
    private final DiscordProperties properties;

    public DiscordNotifier(DiscordProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    @KafkaListener(topics = Topics.JOBS_EVENTS, groupId = "worker-notifier", containerFactory = "eventsListenerFactory")
    public void onEvent(JobEventEnvelope event) {
        if (event.type() != EventType.NEW) {
            return;
        }

        if (properties.webhookUrl() == null || properties.webhookUrl().isBlank()) {
            log.warn("Discord webhook URL not configured, skipping notification for {}:{}",
                    event.source(), event.sourceJobId());
            return;
        }

        Map<String, Object> embed = Map.of(
                "title", event.title() != null ? event.title() : "(無標題)",
                "url", event.url(),
                "description", buildDescription(event),
                "color", 0x5865F2
        );

        Map<String, Object> body = Map.of("embeds", List.of(embed));

        restClient.post()
                .uri(properties.webhookUrl())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Notified Discord for new job source={} sourceJobId={}", event.source(), event.sourceJobId());
    }

    private String buildDescription(JobEventEnvelope event) {
        StringBuilder sb = new StringBuilder();
        if (event.company() != null) {
            sb.append("**公司**：").append(event.company()).append('\n');
        }
        if (event.salaryText() != null) {
            sb.append("**薪資**：").append(event.salaryText()).append('\n');
        }
        sb.append("**來源**：").append(event.source());
        return sb.toString();
    }
}
