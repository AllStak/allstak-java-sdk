package dev.allstak.kafka;

import dev.allstak.AllStak;
import dev.allstak.scope.Scopes;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka {@link ConsumerInterceptor} that copies inbound AllStak trace
 * headers into the active isolation scope so a captured event during
 * processing inherits the producer's trace context.
 *
 * <p>Adds one breadcrumb per poll summarising the batch shape.
 */
public final class AllStakKafkaConsumerInterceptor implements ConsumerInterceptor<Object, Object> {

    @Override
    public ConsumerRecords<Object, Object> onConsume(ConsumerRecords<Object, Object> records) {
        if (records.isEmpty() || AllStak.getClient() == null) return records;

        ConsumerRecord<Object, Object> first = records.iterator().next();
        Header trace = first.headers().lastHeader(AllStakKafkaProducerInterceptor.HEADER_TRACE_ID);
        if (trace != null) {
            String traceId = new String(trace.value(), StandardCharsets.UTF_8);
            Scopes.isolation().setTag("trace.id", traceId);
        }
        AllStak.addBreadcrumb("kafka", "consume " + first.topic(),
                "info", java.util.Map.of("count", records.count(), "topic", first.topic()));
        return records;
    }

    @Override public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {}
    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}
