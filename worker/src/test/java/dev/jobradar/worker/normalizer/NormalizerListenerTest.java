package dev.jobradar.worker.normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.envelope.RawEnvelope;
import dev.jobradar.common.source.Source;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 驗證 add-business-metrics-and-alerting 埋的 jobradar.parse / jobradar.events.published，
 * 以及「parser 內部真的拋例外時，計數後必須重新拋出、不能吞掉」這個高風險項
 * （跟 DiscordNotifier 同一個原則，見 design.md）。
 */
class NormalizerListenerTest {

    private final JobUpsertRepository jobRepository = mock(JobUpsertRepository.class);
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
            public Source source() {
                return Source.YOURATOR;
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                return new NormalizedJob("Title", "Company", null, null, null, null);
            }
        };
        when(jobRepository.upsert(any(Source.class), anyString(), anyString(), any(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(true);

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope(Source.YOURATOR, "123", Instant.now(), "http://example.com/123", payload);

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
            public Source source() {
                return Source.YOURATOR;
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                return new NormalizedJob("Title", "Company", null, null, null, null);
            }
        };
        when(jobRepository.upsert(any(Source.class), anyString(), anyString(), any(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(false);

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope(Source.YOURATOR, "123", Instant.now(), "http://example.com/123", payload);

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
            public Source source() {
                return Source.YOURATOR;
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                throw boom;
            }
        };

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope(Source.YOURATOR, "123", Instant.now(), "http://example.com/123", payload);

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

    /**
     * add-crawl-improvements：內容沒變（content_hash 跟 jobs 表現有值一樣）時，
     * 不該再寫 job_snapshots／raw_documents——這是這次變更真正要解決的問題
     * （實測發現 411 筆真實職缺卻堆出 11,597 筆快照）。jobs 本身的 upsert 仍要照跑
     * （維持 last_seen_at 更新），只有快照/raw document 的寫入被跳過。
     */
    @Test
    void unchangedContentSkipsSnapshotAndRawDocumentWrites() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        NormalizedJob normalized = new NormalizedJob("Title", "Company", null, null, null, null);
        RawPayloadParser parser = new RawPayloadParser() {
            @Override
            public Source source() {
                return Source.YOURATOR;
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                return normalized;
            }
        };
        String contentHash = ContentHash.of(normalized);
        when(jobRepository.findContentHash(Source.YOURATOR, "123")).thenReturn(Optional.of(contentHash));
        when(jobRepository.upsert(any(Source.class), anyString(), anyString(), any(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(false);

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope(Source.YOURATOR, "123", Instant.now(), "http://example.com/123", payload);

        listener.onRaw(envelope);

        verify(rawDocumentRepository, never()).insertIgnore(any(Source.class), anyString(), any(Instant.class), anyString());
        verify(snapshotRepository, never())
                .insertIgnore(any(Source.class), anyString(), any(Instant.class), any(), anyString());
        // jobs 本身的 upsert 不受影響，仍然要跑（維持 last_seen_at 更新，見 D12）
        verify(jobRepository, times(1))
                .upsert(any(Source.class), anyString(), anyString(), any(), anyString(), anyString(), any(Instant.class));
        assertThat(meterRegistry.get("jobradar.snapshot.write")
                        .tag("source", "yourator")
                        .tag("result", "skipped")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /**
     * 內容真的變了（content_hash 跟 jobs 表現有值不同）時，快照/raw document 照樣要寫，
     * 不能因為這次變更而漏抓真正的內容變化。
     */
    @Test
    void changedContentStillWritesSnapshotAndRawDocument() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        NormalizedJob normalized = new NormalizedJob("New Title", "Company", null, null, null, null);
        RawPayloadParser parser = new RawPayloadParser() {
            @Override
            public Source source() {
                return Source.YOURATOR;
            }

            @Override
            public NormalizedJob parse(JsonNode payload) {
                return normalized;
            }
        };
        when(jobRepository.findContentHash(Source.YOURATOR, "123")).thenReturn(Optional.of("some-old-hash-that-differs"));
        when(jobRepository.upsert(any(Source.class), anyString(), anyString(), any(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(false);

        NormalizerListener listener = new NormalizerListener(
                java.util.List.of(parser), jobRepository, snapshotRepository, rawDocumentRepository, kafkaTemplate, meterRegistry);
        RawEnvelope envelope = new RawEnvelope(Source.YOURATOR, "123", Instant.now(), "http://example.com/123", payload);

        listener.onRaw(envelope);

        verify(rawDocumentRepository, times(1)).insertIgnore(any(Source.class), anyString(), any(Instant.class), anyString());
        verify(snapshotRepository, times(1))
                .insertIgnore(any(Source.class), anyString(), any(Instant.class), any(), anyString());
        assertThat(meterRegistry.get("jobradar.snapshot.write")
                        .tag("source", "yourator")
                        .tag("result", "written")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
