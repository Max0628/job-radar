package dev.jobradar.worker.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.jobradar.common.envelope.EventType;
import dev.jobradar.common.envelope.JobEventEnvelope;
import dev.jobradar.common.source.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 這裡最重要的一個測試是 exceptionIsCountedButStillPropagates——這是
 * add-business-metrics-and-alerting design.md 標成最高風險的一項：計數用的
 * try/catch 如果不小心吞掉例外，訊息會被視為處理成功並 commit offset，推播
 * 永久遺失、DLQ 永遠是空的，而且從外部完全看不出異常（真實踩過，見
 * add-platform-observability 對 job-radar-discord webhook 是 placeholder
 * 這件事的記錄）。
 */
class DiscordNotifierTest {

    @Test
    void successfulNotificationRecordsSuccessAndPipelineLatency() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://discord.example.com/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DiscordNotifier notifier = new DiscordNotifier(
                new DiscordProperties("https://discord.example.com/webhook"), builder, meterRegistry);

        Instant scrapedAt = Instant.now().minus(Duration.ofSeconds(30));
        JobEventEnvelope event = new JobEventEnvelope(
                Source.YOURATOR, "123", scrapedAt, "http://example.com/123",
                EventType.NEW, "Backend Engineer", "Acme", "TWD 60000 - 80000");

        notifier.onEvent(event);

        assertThat(meterRegistry.get("jobradar.notification").tag("result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("jobradar.pipeline.latency").timer().count()).isEqualTo(1L);
        server.verify();
    }

    @Test
    void exceptionIsCountedButStillPropagates() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://discord.example.com/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DiscordNotifier notifier = new DiscordNotifier(
                new DiscordProperties("https://discord.example.com/webhook"), builder, meterRegistry);

        JobEventEnvelope event = new JobEventEnvelope(
                Source.YOURATOR, "123", Instant.now(), "http://example.com/123",
                EventType.NEW, "Backend Engineer", "Acme", "TWD 60000 - 80000");

        // 例外必須向上傳播給 DefaultErrorHandler，否則 offset 會被 commit、訊息永久遺失
        assertThatThrownBy(() -> notifier.onEvent(event)).isInstanceOf(Exception.class);

        assertThat(meterRegistry.get("jobradar.notification").tag("result", "failure").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("jobradar.notification").tag("result", "success").counter()).isNull();
        // 失敗時不該記錄延遲——這筆還沒真的送達
        assertThat(meterRegistry.find("jobradar.pipeline.latency").timer()).isNull();
        server.verify();
    }

    @Test
    void changedEventTypeIsIgnored() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 不預期任何請求

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DiscordNotifier notifier = new DiscordNotifier(
                new DiscordProperties("https://discord.example.com/webhook"), builder, meterRegistry);

        JobEventEnvelope event = new JobEventEnvelope(
                Source.YOURATOR, "123", Instant.now(), "http://example.com/123",
                EventType.CHANGED, "Backend Engineer", "Acme", "TWD 60000 - 80000");

        notifier.onEvent(event);

        assertThat(meterRegistry.find("jobradar.notification").counter()).isNull();
        server.verify();
    }

    @Test
    void blankWebhookUrlSkipsWithoutError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DiscordNotifier notifier = new DiscordNotifier(new DiscordProperties(""), builder, meterRegistry);

        JobEventEnvelope event = new JobEventEnvelope(
                Source.YOURATOR, "123", Instant.now(), "http://example.com/123",
                EventType.NEW, "Backend Engineer", "Acme", "TWD 60000 - 80000");

        notifier.onEvent(event);

        assertThat(meterRegistry.find("jobradar.notification").counter()).isNull();
        server.verify();
    }
}
