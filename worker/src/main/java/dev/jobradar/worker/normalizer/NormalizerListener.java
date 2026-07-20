package dev.jobradar.worker.normalizer;

import dev.jobradar.common.envelope.EventType;
import dev.jobradar.common.envelope.JobEventEnvelope;
import dev.jobradar.common.envelope.RawEnvelope;
import dev.jobradar.common.kafka.Topics;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * jobs.raw 消費者：正規化 + 冪等 upsert + 快照 + raw document，
 * 只有真正新增的一列（見 JobRepository.upsert）才發 NEW 事件（見 architecture.md D5）。
 */
@Component
public class NormalizerListener {

    private static final Logger log = LoggerFactory.getLogger(NormalizerListener.class);

    private final Map<String, RawPayloadParser> parsersBySource;
    private final JobRepository jobRepository;
    private final JobSnapshotRepository snapshotRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public NormalizerListener(
            List<RawPayloadParser> parsers,
            JobRepository jobRepository,
            JobSnapshotRepository snapshotRepository,
            RawDocumentRepository rawDocumentRepository,
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        this.parsersBySource = parsers.stream().collect(Collectors.toMap(RawPayloadParser::source, p -> p));
        this.jobRepository = jobRepository;
        this.snapshotRepository = snapshotRepository;
        this.rawDocumentRepository = rawDocumentRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = Topics.JOBS_RAW, groupId = "worker-normalizer", containerFactory = "rawListenerFactory")
    public void onRaw(RawEnvelope envelope) {
        RawPayloadParser parser = parsersBySource.get(envelope.source());
        if (parser == null) {
            log.warn("No payload parser registered for source={}", envelope.source());
            return;
        }

        NormalizedJob normalized = parser.parse(envelope.payload());
        String contentHash = ContentHash.of(normalized);
        String payloadJson = envelope.payload().toString();

        rawDocumentRepository.insertIgnore(envelope.source(), envelope.sourceJobId(), envelope.scrapedAt(), payloadJson);
        snapshotRepository.insertIgnore(envelope.source(), envelope.sourceJobId(), envelope.scrapedAt(), normalized, contentHash);

        boolean isNew = jobRepository.upsert(
                envelope.source(), envelope.sourceJobId(), envelope.url(), normalized,
                contentHash, payloadJson, envelope.scrapedAt());

        if (isNew) {
            JobEventEnvelope event = new JobEventEnvelope(
                    envelope.source(), envelope.sourceJobId(), envelope.scrapedAt(), envelope.url(),
                    EventType.NEW, normalized.title(), normalized.company(), formatSalary(normalized));
            String key = envelope.source() + ":" + envelope.sourceJobId();
            kafkaTemplate.send(Topics.JOBS_EVENTS, key, event);
            log.info("New job upserted source={} sourceJobId={}", envelope.source(), envelope.sourceJobId());
        } else {
            log.debug("Existing job re-upserted source={} sourceJobId={}", envelope.source(), envelope.sourceJobId());
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
