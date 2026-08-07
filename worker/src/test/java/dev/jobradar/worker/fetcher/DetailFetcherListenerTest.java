package dev.jobradar.worker.fetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.envelope.DiscoveredEnvelope;
import dev.jobradar.common.repository.JobExistenceRepository;
import dev.jobradar.common.source.Source;
import dev.jobradar.common.source.SourceBlockedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 疑似被來源網站封鎖時自動停用該來源所有查詢（見 add-104-source/design.md「自動關閉」
 * 決策）：detail fetch 拋 SourceBlockedException 要觸發 disableAllForSource，且例外
 * 仍要往外拋（讓 KafkaConsumerConfig 的 addNotRetryableExceptions 接手送 DLQ，不重試）。
 */
class DetailFetcherListenerTest {

    private final JobExistenceRepository jobExistenceRepository = mock(JobExistenceRepository.class);
    private final SearchQueryDisableRepository searchQueryDisableRepository = mock(SearchQueryDisableRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void blockedDetailFetchDisablesSourceAndRethrows() {
        when(jobExistenceRepository.exists(Source.JOB104, "123")).thenReturn(false);

        DetailScraper scraper = new DetailScraper() {
            @Override
            public Source source() {
                return Source.JOB104;
            }

            @Override
            public JsonNode fetch(String sourceJobId, String url) {
                throw new SourceBlockedException(Source.JOB104, "104 detail returned 403", null);
            }
        };

        DetailFetcherListener listener = new DetailFetcherListener(
                List.of(scraper), jobExistenceRepository, searchQueryDisableRepository, kafkaTemplate, meterRegistry);

        JsonNode payload = new ObjectMapper().createObjectNode();
        DiscoveredEnvelope envelope = new DiscoveredEnvelope(
                Source.JOB104, "123", Instant.now(), "https://www.104.com.tw/job/abc12", true, "abc12", payload);

        assertThatThrownBy(() -> listener.onDiscovered(envelope))
                .isInstanceOf(SourceBlockedException.class);

        verify(searchQueryDisableRepository).disableAllForSource(org.mockito.ArgumentMatchers.eq(Source.JOB104), anyString());
    }

    /**
     * 缺 JSON-LD 這類「內容不可用、重試不會變」的情況要能跳過而不重新拋出——不然
     * Kafka error handler 會重試 3 次再進 DLQ，同一筆問題職缺每次被重新掃到都會重演一次
     * (實際發生過：同一筆 Yourator 職缺灌了 35 筆 jobs.discovered.dlq 訊息)。
     */
    @Test
    void contentUnavailableIsSkippedWithoutThrowingOrPublishing() {
        when(jobExistenceRepository.exists(Source.YOURATOR, "999")).thenReturn(false);

        DetailScraper scraper = new DetailScraper() {
            @Override
            public Source source() {
                return Source.YOURATOR;
            }

            @Override
            public JsonNode fetch(String sourceJobId, String url) {
                throw new DetailContentUnavailableException("No JobPosting JSON-LD found at " + url);
            }
        };

        DetailFetcherListener listener = new DetailFetcherListener(
                List.of(scraper), jobExistenceRepository, searchQueryDisableRepository, kafkaTemplate, meterRegistry);

        JsonNode payload = new ObjectMapper().createObjectNode();
        DiscoveredEnvelope envelope = new DiscoveredEnvelope(
                Source.YOURATOR, "999", Instant.now(),
                "https://www.yourator.co/companies/sinyi/jobs/43179", true,
                "https://www.yourator.co/companies/sinyi/jobs/43179", payload);

        listener.onDiscovered(envelope);

        verifyNoInteractions(kafkaTemplate);
        assertThat(meterRegistry.get("jobradar.detail.skip")
                        .tag("source", "yourator")
                        .tag("reason", "content_unavailable")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
