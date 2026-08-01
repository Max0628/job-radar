package dev.jobradar.worker.reporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.jobradar.worker.notifier.DiscordProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ScanSummaryReporterTest {

    @Test
    void reportsDueRunAndMarksItReported() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://discord.example.com/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        ScrapeRunReportRepository repository = mock(ScrapeRunReportRepository.class);
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(15));
        Instant finishedAt = Instant.now().minus(Duration.ofMinutes(11));
        UnreportedScrapeRun run = new UnreportedScrapeRun(
                1L, "yourator", "light", false, startedAt, finishedAt, 2, 40);
        when(repository.findUnreportedFinishedRuns(any(Instant.class))).thenReturn(List.of(run));
        when(repository.countNewJobs(eq("yourator"), any(Instant.class), any(Instant.class))).thenReturn(3);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScanSummaryReporter reporter = new ScanSummaryReporter(
                repository, new DiscordProperties("https://discord.example.com/webhook"), builder, meterRegistry);

        reporter.reportDueRuns();

        verify(repository).markReported(1L);
        assertThat(meterRegistry.get("jobradar.scan.summary.report")
                        .tag("result", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }

    @Test
    void noDueRunsSendsNothing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 不預期任何請求

        ScrapeRunReportRepository repository = mock(ScrapeRunReportRepository.class);
        when(repository.findUnreportedFinishedRuns(any(Instant.class))).thenReturn(List.of());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScanSummaryReporter reporter = new ScanSummaryReporter(
                repository, new DiscordProperties("https://discord.example.com/webhook"), builder, meterRegistry);

        reporter.reportDueRuns();

        verify(repository, never()).markReported(anyLong());
        server.verify();
    }

    /**
     * 單筆失敗只記 log、繼續處理下一筆——不像 DiscordNotifier 需要重新拋出例外
     * （這裡是排程輪詢，沒有 Kafka DLQ 機制依賴例外傳播，見類別註解）。
     */
    @Test
    void oneRunFailingDoesNotBlockOthers() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://discord.example.com/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
        server.expect(requestTo("https://discord.example.com/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        ScrapeRunReportRepository repository = mock(ScrapeRunReportRepository.class);
        Instant startedAt = Instant.now().minus(Duration.ofMinutes(15));
        Instant finishedAt = Instant.now().minus(Duration.ofMinutes(11));
        UnreportedScrapeRun failingRun = new UnreportedScrapeRun(
                1L, "yourator", "light", false, startedAt, finishedAt, 1, 10);
        UnreportedScrapeRun succeedingRun = new UnreportedScrapeRun(
                2L, "cakeresume", "deep", true, startedAt, finishedAt, 5, 90);
        when(repository.findUnreportedFinishedRuns(any(Instant.class)))
                .thenReturn(List.of(failingRun, succeedingRun));
        when(repository.countNewJobs(anyString(), any(Instant.class), any(Instant.class))).thenReturn(1);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScanSummaryReporter reporter = new ScanSummaryReporter(
                repository, new DiscordProperties("https://discord.example.com/webhook"), builder, meterRegistry);

        reporter.reportDueRuns();

        verify(repository, never()).markReported(1L);
        verify(repository).markReported(2L);
        assertThat(meterRegistry.get("jobradar.scan.summary.report")
                        .tag("result", "failure")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("jobradar.scan.summary.report")
                        .tag("result", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        server.verify();
    }
}
