package dev.jobradar.worker.notifier;

import dev.jobradar.common.envelope.EventType;
import dev.jobradar.common.envelope.JobEventEnvelope;
import dev.jobradar.common.kafka.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
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
    private final MeterRegistry meterRegistry;

    // 注入 RestClient.Builder（比照 YouratorListScraper），而不是用 RestClient.builder()
    // 靜態工廠——後者不會被 Spring Boot 的 observability 自動組態納入，這個呼叫本來
    // 完全沒有 client 端 HTTP 指標，而 Discord 恰好是使用者可感知的最後一哩路
    // （見 design.md「對外 HTTP 呼叫一律可被 instrument」）。
    public DiscordNotifier(DiscordProperties properties, RestClient.Builder restClientBuilder, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.restClient = restClientBuilder.build();
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

        try {
            restClient.post()
                    .uri(properties.webhookUrl())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 計數後 MUST 重新拋出：現有的三次重試 + DLQ 機制（見 KafkaConsumerConfig）
            // 依賴這個例外往上傳播才會觸發。吞掉的話訊息會被視為處理成功並 commit
            // offset，這筆推播永久遺失、DLQ 永遠是空的，且從外部完全觀察不出異常
            // （這正是這次發現 job-radar-discord webhook 是 placeholder、通知已經
            // 默默壞了至少 6 天的原因——見 add-platform-observability 的實測記錄）。
            meterRegistry.counter("jobradar.notification", "result", "failure").increment();
            throw e;
        }

        meterRegistry.counter("jobradar.notification", "result", "success").increment();

        // SLO-1 的 SLI 來源：從 collector 發現（scrapedAt）到這裡推播成功的端到端延遲。
        // serviceLevelObjectives 在 5 分鐘處設一個 bucket 邊界，讓「99% < 5 分鐘」這個
        // SLO 可以用「bucket 內樣本數 / 總樣本數」精確計算，不需要 histogram_quantile()
        // 估算（見 design.md）。不加 source label：這個指標描述 pipeline 整體健康，
        // 分來源比較另外查 jobradar.scan.duration。
        Duration latency = Duration.between(event.scrapedAt(), Instant.now());
        Timer.builder("jobradar.pipeline.latency")
                .serviceLevelObjectives(Duration.ofMinutes(5))
                .register(meterRegistry)
                .record(latency);

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
