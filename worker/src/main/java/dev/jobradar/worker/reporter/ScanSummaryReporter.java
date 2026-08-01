package dev.jobradar.worker.reporter;

import dev.jobradar.worker.notifier.DiscordProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 掃描摘要回報（見 architecture.md D21）：每分鐘檢查有沒有已結束超過 10 分鐘、還沒
 * 回報過的成功掃描，組訊息送 Discord（跟告警共用同一個 webhook，沿用既有
 * {@link DiscordProperties}）。
 *
 * 跟 {@link dev.jobradar.worker.notifier.DiscordNotifier} 的錯誤處理原則刻意不同：
 * 這裡單筆失敗只記 log、繼續處理下一筆，不重新拋出例外——這是排程輪詢，不是 Kafka
 * consumer，沒有重試/DLQ 機制依賴例外傳播；`report_sent_at` 只在成功送出後才更新，
 * 下一輪輪詢會自然重試這筆失敗的，不需要靠拋例外中斷整個批次來保證不遺漏。
 */
@Component
public class ScanSummaryReporter {

    private static final Logger log = LoggerFactory.getLogger(ScanSummaryReporter.class);
    private static final Duration REPORT_DELAY = Duration.ofMinutes(10);

    private final ScrapeRunReportRepository repository;
    private final RestClient restClient;
    private final DiscordProperties properties;
    private final MeterRegistry meterRegistry;

    public ScanSummaryReporter(
            ScrapeRunReportRepository repository,
            DiscordProperties properties,
            RestClient.Builder restClientBuilder,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelay = 60_000)
    public void reportDueRuns() {
        if (properties.webhookUrl() == null || properties.webhookUrl().isBlank()) {
            log.warn("Discord webhook URL not configured, skipping scan summary report");
            return;
        }

        List<UnreportedScrapeRun> dueRuns = repository.findUnreportedFinishedRuns(Instant.now().minus(REPORT_DELAY));
        for (UnreportedScrapeRun run : dueRuns) {
            try {
                reportOne(run);
            } catch (Exception e) {
                log.warn("Failed to send scan summary report for run id={} source={}", run.id(), run.source(), e);
                meterRegistry.counter("jobradar.scan.summary.report", "result", "failure").increment();
            }
        }
    }

    private void reportOne(UnreportedScrapeRun run) {
        int newJobs = repository.countNewJobs(run.source(), run.startedAt(), run.finishedAt());
        Duration duration = Duration.between(run.startedAt(), run.finishedAt());

        Map<String, Object> embed = Map.of(
                "title", run.source() + " 掃描完成",
                "description", buildDescription(run, newJobs, duration),
                "color", 0x2ECC71
        );
        Map<String, Object> body = Map.of("embeds", List.of(embed));

        restClient.post()
                .uri(properties.webhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        repository.markReported(run.id());
        meterRegistry.counter("jobradar.scan.summary.report", "result", "success").increment();
    }

    private String buildDescription(UnreportedScrapeRun run, int newJobs, Duration duration) {
        StringBuilder sb = new StringBuilder();
        sb.append("**模式**：").append("deep".equals(run.scanMode()) ? "深掃" : "淺掃").append('\n');
        sb.append("**提早停止**：").append(run.terminatedEarly() ? "是" : "否").append('\n');
        sb.append("**掃描頁數**：").append(run.pagesScanned()).append('\n');
        sb.append("**掃描筆數**：").append(run.jobsSeen()).append('\n');
        sb.append("**新增筆數**：").append(newJobs).append('\n');
        sb.append("**耗時**：").append(duration.toSeconds()).append(" 秒");
        return sb.toString();
    }
}
