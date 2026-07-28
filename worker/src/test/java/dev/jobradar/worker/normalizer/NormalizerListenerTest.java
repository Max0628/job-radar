package dev.jobradar.worker.normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.envelope.RawEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 驗證 add-business-metrics-and-alerting 埋的 jobradar.parse / jobradar.events.published，
 * 以及「parser 內部真的拋例外時，計數後必須重新拋出、不能吞掉」這個高風險項
 * （跟 DiscordNotifier 同一個原則，見 design.md）。
 */
class NormalizerListenerTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobSnapshotRepository snapshotRepository = mock(JobSnapshotRepository.class);
    private final RawDocumentRepository rawDocumentRepository = mock(RawDocumentRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final JsonNode payload = new ObjectMapper().createObjectNode();

    @Test
    void successfulNewJobRecordsParseSuccessAndEventPublished() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RawPayloadParser parser = new RawPayloadParser() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                return new NormalizedJob("Title", "Company", null, null, null, null);
            }
        };
        when(jobRepository.upsert(anyString(), anyString(), anyString(), any(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(true);

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope("yourator", "123", Instant.now(), "http://example.com/123", payload);

        listener.onRaw(envelope);

        assertThat(meterRegistry.get("jobradar.parse")
                        .tag("source", "yourator")
                        .tag("result", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("jobradar.events.published")
                        .tag("source", "yourator")
                        .tag("type", "NEW")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void existingJobDoesNotPublishEventButStillRecordsParseSuccess() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RawPayloadParser parser = new RawPayloadParser() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                return new NormalizedJob("Title", "Company", null, null, null, null);
            }
        };
        when(jobRepository.upsert(anyString(), anyString(), anyString(), any(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(false);

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope("yourator", "123", Instant.now(), "http://example.com/123", payload);

        listener.onRaw(envelope);

        assertThat(meterRegistry.get("jobradar.parse").tag("source", "yourator").tag("result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("jobradar.events.published").counter()).isNull();
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void parserExceptionIsCountedAndRethrown() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RuntimeException boom = new RuntimeException("unexpected parser bug");
        RawPayloadParser parser = new RawPayloadParser() {
            @Override
            public String source() {
                return "yourator";
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                throw boom;
            }
        };

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope("yourator", "123", Instant.now(), "http://example.com/123", payload);

        // 例外必須向上傳播，不能被計數邏輯吞掉——否則訊息會被視為處理成功並 commit
        // offset，違反 D5 的 at-least-once 保證，且從外部完全觀察不出異常。
        assertThatThrownBy(() -> listener.onRaw(envelope)).isSameAs(boom);

        assertThat(meterRegistry.get("jobradar.parse")
                        .tag("source", "yourator")
                        .tag("result", "failure")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        verifyNoInteractions(kafkaTemplate);
    }
}
