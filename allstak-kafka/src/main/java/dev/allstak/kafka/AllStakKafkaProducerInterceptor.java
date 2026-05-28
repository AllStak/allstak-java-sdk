package dev.allstak.kafka;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka {@link ProducerInterceptor} that stamps every outbound record with
 * AllStak trace headers so a downstream consumer joins the same trace.
 * Wire on a Producer:
 *
 * <pre>{@code
 * props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,
 *           AllStakKafkaProducerInterceptor.class.getName());
 * }</pre>
 *
 * <p>Trace-header propagation is unconditional here because the broker is
 * always on the customer's own infrastructure (not a third-party host),
 * so {@code tracePropagationTargets} is not consulted.
 */
public final class AllStakKafkaProducerInterceptor implements ProducerInterceptor<Object, Object> {

    public static final String HEADER_TRACE_ID = "x-allstak-trace-id";
    public static final String HEADER_SPAN_ID  = "x-allstak-span-id";

    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        AllStakClient client = AllStak.getClient();
        if (client == null) return record;
        Headers headers = record.headers();
        if (headers.lastHeader(HEADER_TRACE_ID) == null) {
            headers.add(HEADER_TRACE_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            headers.add(HEADER_SPAN_ID,
                    UUID.randomUUID().toString().substring(0, 16).getBytes(StandardCharsets.UTF_8));
        }
        AllStak.addBreadcrumb("kafka", "produce " + record.topic(),
                "info", java.util.Map.of("partition", record.partition() == null ? "-" : record.partition()));
        return record;
    }

    @Override public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}
    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}
