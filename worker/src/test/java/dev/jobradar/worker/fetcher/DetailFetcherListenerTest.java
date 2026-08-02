package dev.jobradar.worker.fetcher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobradar.common.envelope.DiscoveredEnvelope;
import dev.jobradar.common.repository.JobExistenceRepository;
import dev.jobradar.common.source.Source;
import dev.jobradar.common.source.SourceBlockedException;
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
                List.of(scraper), jobExistenceRepository, searchQueryDisableRepository, kafkaTemplate);

        JsonNode payload = new ObjectMapper().createObjectNode();
        DiscoveredEnvelope envelope = new DiscoveredEnvelope(
                Source.JOB104, "123", Instant.now(), "https://www.104.com.tw/job/abc12", true, "abc12", payload);

        assertThatThrownBy(() -> listener.onDiscovered(envelope))
                .isInstanceOf(SourceBlockedException.class);

        verify(searchQueryDisableRepository).disableAllForSource(org.mockito.ArgumentMatchers.eq(Source.JOB104), anyString());
    }
}
