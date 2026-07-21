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
 * jobs.discovered 消費者：per-source 分流是否需要真的打 detail HTTP（見 D3、Phase 002 design.md D3）。
 *
 * - needsDetail=true（如 Yourator）：list payload 不完整，需另外打 detail 頁；
 *   已存在資料庫的職缺不重打（省去昂貴的 HTTP 呼叫），限速在各來源的 DetailScraper 實作內處理，
 *   此處不重試（交給 Kafka error handler，見 config）。
 * - needsDetail=false（如 CakeResume）：discovered payload 已完整，無 HTTP 成本，
 *   每次都放行轉成 RawEnvelope——刻意不做「已存在跳過」，讓 Normalizer 每輪都能更新
 *   last_seen_at（見 duplicate-prevention spec，冪等 upsert 需要職缺持續被重新看到才能標記
 *   still-active）。
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
        if (envelope.needsDetail()) {
            fetchDetailThenPublish(envelope);
        } else {
            publishRaw(envelope, envelope.payload());
        }
    }

    private void fetchDetailThenPublish(DiscoveredEnvelope envelope) {
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

        JsonNode payload = scraper.fetch(envelope.sourceJobId(), envelope.detailUrl());
        publishRaw(envelope, payload);
    }

    private void publishRaw(DiscoveredEnvelope envelope, JsonNode payload) {
        RawEnvelope rawEnvelope = new RawEnvelope(
                envelope.source(), envelope.sourceJobId(), Instant.now(), envelope.url(), payload);
        String key = envelope.source() + ":" + envelope.sourceJobId();
        kafkaTemplate.send(Topics.JOBS_RAW, key, rawEnvelope);

        log.info("Published raw source={} sourceJobId={} viaDetailFetch={}",
                envelope.source(), envelope.sourceJobId(), envelope.needsDetail());
    }
}
