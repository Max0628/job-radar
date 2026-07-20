package dev.jobradar.worker.fetcher;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobradar.common.envelope.DiscoveredEnvelope;
import dev.jobradar.common.envelope.RawEnvelope;
import dev.jobradar.common.kafka.Topics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * jobs.discovered 消費者：只對資料庫中尚未存在的職缺才呼叫 detail scraper（見 D3）。
 * 限速在各來源的 DetailScraper 實作內處理，此處不重試（交給 Kafka error handler，見 config）。
 */
@Component
public class DetailFetcherListener {

    private static final Logger log = LoggerFactory.getLogger(DetailFetcherListener.class);

    private final Map<String, DetailScraper> scrapersBySource;
    private final JobExistenceRepository jobExistenceRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DetailFetcherListener(
            List<DetailScraper> scrapers,
            JobExistenceRepository jobExistenceRepository,
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.scrapersBySource = scrapers.stream().collect(Collectors.toMap(DetailScraper::source, s -> s));
        this.jobExistenceRepository = jobExistenceRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = Topics.JOBS_DISCOVERED, groupId = "worker-fetcher", containerFactory = "discoveredListenerFactory")
    public void onDiscovered(DiscoveredEnvelope envelope) {
        if (jobExistenceRepository.exists(envelope.source(), envelope.sourceJobId())) {
            log.debug("Job already known, skipping detail fetch source={} sourceJobId={}",
                    envelope.source(), envelope.sourceJobId());
            return;
        }

        DetailScraper scraper = scrapersBySource.get(envelope.source());
        if (scraper == null) {
            log.warn("No detail scraper registered for source={}", envelope.source());
            return;
        }

        JsonNode payload = scraper.fetch(envelope.sourceJobId(), envelope.url());

        RawEnvelope rawEnvelope = new RawEnvelope(
                envelope.source(), envelope.sourceJobId(), Instant.now(), envelope.url(), payload);
        String key = envelope.source() + ":" + envelope.sourceJobId();
        kafkaTemplate.send(Topics.JOBS_RAW, key, rawEnvelope);

        log.info("Fetched detail source={} sourceJobId={}", envelope.source(), envelope.sourceJobId());
    }
}
