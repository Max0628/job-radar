package dev.jobradar.worker.config;

import dev.jobradar.common.envelope.DiscoveredEnvelope;
import dev.jobradar.common.envelope.JobEventEnvelope;
import dev.jobradar.common.envelope.RawEnvelope;
import dev.jobradar.common.kafka.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 每個 topic 各自的 consumer group（見 architecture.md D8）+ 固定型別 JsonDeserializer
 * （因為 producer 端關閉 type headers，見 D3 附錄的 envelope 型別拆分決定）。
 * 三次重試後進 &lt;topic&gt;.dlq（見 design.md 錯誤處理）。
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DiscoveredEnvelope> discoveredListenerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        return buildFactory(DiscoveredEnvelope.class, "worker-fetcher", Topics.JOBS_DISCOVERED_DLQ, kafkaTemplate);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, RawEnvelope> rawListenerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        return buildFactory(RawEnvelope.class, "worker-normalizer", Topics.JOBS_RAW_DLQ, kafkaTemplate);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, JobEventEnvelope> eventsListenerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        return buildFactory(JobEventEnvelope.class, "worker-notifier", Topics.JOBS_EVENTS_DLQ, kafkaTemplate);
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> buildFactory(
            Class<T> targetType, String groupId, String dlqTopic, KafkaTemplate<String, Object> kafkaTemplate) {

        JsonDeserializer<T> jsonDeserializer = new JsonDeserializer<>(targetType, false);
        jsonDeserializer.setRemoveTypeHeaders(true);
        ErrorHandlingDeserializer<T> valueDeserializer = new ErrorHandlingDeserializer<>(jsonDeserializer);

        ConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProps(groupId), new StringDeserializer(), valueDeserializer);

        ConcurrentKafkaListenerContainerFactory<String, T> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new org.apache.kafka.common.TopicPartition(dlqTopic, record.partition()));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L)));

        return factory;
    }

    private java.util.Map<String, Object> consumerProps(String groupId) {
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }
}
