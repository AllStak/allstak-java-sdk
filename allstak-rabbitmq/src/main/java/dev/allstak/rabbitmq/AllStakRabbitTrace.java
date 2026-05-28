package dev.allstak.rabbitmq;

import com.rabbitmq.client.AMQP;
import dev.allstak.AllStak;
import dev.allstak.scope.Scopes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RabbitMQ trace-header helper. Apply to outbound {@link AMQP.BasicProperties}
 * via {@link #withHeaders(AMQP.BasicProperties)} and harvest on inbound
 * delivery with {@link #harvest(AMQP.BasicProperties)}.
 */
public final class AllStakRabbitTrace {

    public static final String HEADER_TRACE_ID = "x-allstak-trace-id";
    public static final String HEADER_SPAN_ID  = "x-allstak-span-id";

    private AllStakRabbitTrace() {}

    public static AMQP.BasicProperties withHeaders(AMQP.BasicProperties properties) {
        if (AllStak.getClient() == null) return properties;
        Map<String, Object> headers = properties == null || properties.getHeaders() == null
                ? new HashMap<>()
                : new HashMap<>(properties.getHeaders());
        headers.putIfAbsent(HEADER_TRACE_ID, UUID.randomUUID().toString());
        headers.putIfAbsent(HEADER_SPAN_ID,  UUID.randomUUID().toString().substring(0, 16));
        return new AMQP.BasicProperties.Builder()
                .appId(properties == null ? null : properties.getAppId())
                .contentEncoding(properties == null ? null : properties.getContentEncoding())
                .contentType(properties == null ? null : properties.getContentType())
                .headers(headers)
                .build();
    }

    public static void harvest(AMQP.BasicProperties properties) {
        if (properties == null || properties.getHeaders() == null) return;
        Object trace = properties.getHeaders().get(HEADER_TRACE_ID);
        if (trace != null) Scopes.isolation().setTag("trace.id", trace.toString());
    }
}
