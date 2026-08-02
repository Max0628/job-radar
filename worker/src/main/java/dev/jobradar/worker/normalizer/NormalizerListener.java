package dev.jobradar.worker.normalizer;

import dev.jobradar.common.envelope.EventType;
import dev.jobradar.common.envelope.JobEventEnvelope;
import dev.jobradar.common.envelope.RawEnvelope;
import dev.jobradar.common.kafka.Topics;
import dev.jobradar.common.source.Source;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * jobs.raw 消費者：正規化 + 冪等 upsert + 快照 + raw document，
 * 只有真正新增的一列（見 JobUpsertRepository.upsert）才發 NEW 事件（見 architecture.md D5）。
 */
@Component
public class NormalizerListener {

    private static final Logger log = LoggerFactory.getLogger(NormalizerListener.class);

    private final Map<Source, RawPayloadParser> parsersBySource;
    private final JobUpsertRepository jobRepository;
    private final JobSnapshotRepository snapshotRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public NormalizerListener(
            List<RawPayloadParser> parsers,
            JobUpsertRepository jobRepository,
            JobSnapshotRepository snapshotRepository,
            RawDocumentRepository rawDocumentRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            MeterRegistry meterRegistry
    ) {
        this.parsersBySource = Source.indexBy(parsers, RawPayloadParser::source);
        this.jobRepository = jobRepository;
        this.snapshotRepository = snapshotRepository;
        this.rawDocumentRepository = rawDocumentRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = Topics.JOBS_RAW, groupId = "worker-normalizer", containerFactory = "rawListenerFactory")
    public void onRaw(RawEnvelope envelope) {
        RawPayloadParser parser = parsersBySource.get(envelope.source());
        if (parser == null) {
            log.warn("No payload parser registered for source={}", envelope.source());
            return;
        }

        // parse() 本身目前的設計是「欄位級」優雅降級（例如 postedAt 解析失敗時該欄位留
        // null，見 add-job-posted-date/design.md），不會整筆回傳 null 或拋例外。
        // 這裡的 try/catch 涵蓋的是「parser 內部真的出現非預期例外」這種未被個別欄位
        // 防禦邏輯擋住的情況——計數後必須重新拋出，讓既有的三次重試 + DLQ 機制不變
        // （跟 DiscordNotifier 同一個原則，見 design.md）。
        try {
            NormalizedJob normalized = parser.parse(envelope.payload());
            String contentHash = ContentHash.of(normalized);
            String payloadJson = envelope.payload().toString();

            // 內容沒變就不寫 job_snapshots／raw_documents（見 add-crawl-improvements
            // design.md）：同一筆職缺每次被掃到都會走到這裡，2 小時一輪、上百筆職缺，
            // 內容沒變的話兩張表會無意義地一直長大（實測過 411 筆真實職缺卻堆出
            // 11,597 筆快照，其中一筆職缺甚至有 61 份幾乎一樣的快照）。只有真的是
            // 新職缺、或內容雜湊真的不同時才寫；jobs 本身的 upsert 不受影響，
            // 每次都跑，維持 last_seen_at 更新。
            String previousHash = jobRepository.findContentHash(envelope.source(), envelope.sourceJobId())
                    .orElse(null);
            boolean contentChanged = previousHash == null || !previousHash.equals(contentHash);

            if (contentChanged) {
                rawDocumentRepository.insertIgnore(envelope.source(), envelope.sourceJobId(), envelope.scrapedAt(), payloadJson);
                snapshotRepository.insertIgnore(envelope.source(), envelope.sourceJobId(), envelope.scrapedAt(), normalized, contentHash);
                meterRegistry.counter("jobradar.snapshot.write", "source", envelope.source().value(), "result", "written").increment();
            } else {
                meterRegistry.counter("jobradar.snapshot.write", "source", envelope.source().value(), "result", "skipped").increment();
            }

            boolean isNew = jobRepository.upsert(
                    envelope.source(), envelope.sourceJobId(), envelope.url(), normalized,
                    contentHash, payloadJson, envelope.scrapedAt());

            meterRegistry.counter("jobradar.parse", "source", envelope.source().value(), "result", "success").increment();

            if (isNew) {
                JobEventEnvelope event = new JobEventEnvelope(
                        envelope.source(), envelope.sourceJobId(), envelope.scrapedAt(), envelope.url(),
                        EventType.NEW, normalized.title(), normalized.company(), formatSalary(normalized));
                String key = envelope.source() + ":" + envelope.sourceJobId();
                kafkaTemplate.send(Topics.JOBS_EVENTS, key, event);
                meterRegistry.counter("jobradar.events.published", "source", envelope.source().value(), "type", "NEW").increment();
                log.info("New job upserted source={} sourceJobId={}", envelope.source(), envelope.sourceJobId());
            } else {
                log.debug("Existing job re-upserted source={} sourceJobId={}", envelope.source(), envelope.sourceJobId());
            }
        } catch (Exception e) {
            meterRegistry.counter("jobradar.parse", "source", envelope.source().value(), "result", "failure").increment();
            throw e;
        }
    }

    private String formatSalary(NormalizedJob normalized) {
        if (normalized.salaryMin() == null && normalized.salaryMax() == null) {
            return null;
        }
        String currency = normalized.salaryCurrency() != null ? normalized.salaryCurrency() : "";
        return "%s %s - %s".formatted(currency, normalized.salaryMin(), normalized.salaryMax()).trim();
    }
}
