package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.model.RequestContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class AllStakKafkaSupport {
    static final String HEADER_TRACE_ID = "x-allstak-trace-id";
    static final String HEADER_REQUEST_ID = "x-allstak-request-id";
    static final String HEADER_PARENT_SPAN_ID = "x-allstak-parent-span-id";
    static final String HEADER_TRACEPARENT = "traceparent";

    private AllStakKafkaSupport() {}

    static KafkaContext fromRecord(ConsumerRecord<?, ?> record, String fallbackGroupId) {
        Headers headers = record != null ? record.headers() : null;
        String traceId = header(headers, HEADER_TRACE_ID);
        if (isBlank(traceId)) traceId = traceIdFromTraceparent(header(headers, HEADER_TRACEPARENT));
        if (isBlank(traceId)) traceId = UUID.randomUUID().toString();

        String requestId = header(headers, HEADER_REQUEST_ID);
        if (isBlank(requestId) && record != null) {
            requestId = "kafka-" + record.topic() + "-" + record.partition() + "-" + record.offset();
        }
        if (isBlank(requestId)) requestId = "kafka-" + UUID.randomUUID();

        String parentSpanId = header(headers, HEADER_PARENT_SPAN_ID);
        String deliveryAttempt = header(headers, "kafka_deliveryAttempt");
        if (isBlank(deliveryAttempt)) deliveryAttempt = header(headers, "delivery-attempt");

        String topic = record != null ? record.topic() : null;
        Integer partition = record != null ? record.partition() : null;
        Long offset = record != null ? record.offset() : null;
        Long timestamp = record != null ? record.timestamp() : null;
        String keyHash = record != null ? hashKey(record.key()) : null;
        boolean dlt = isDltTopic(topic);
        return new KafkaContext(traceId, requestId, randomSpanId(), parentSpanId, topic,
                partition, offset, fallbackGroupId, keyHash, timestamp, deliveryAttempt, dlt);
    }

    static KafkaContext injectProducerHeaders(ProducerRecord<?, ?> record) {
        RequestContext req = AllStakClient.getRequestContext();
        String traceId = req != null && !isBlank(req.getTraceId()) ? req.getTraceId() : UUID.randomUUID().toString();
        String requestId = req != null && !isBlank(req.getRequestId()) ? req.getRequestId() : "kafka-" + UUID.randomUUID();
        String spanId = randomSpanId();
        Headers headers = record.headers();
        putIfAbsent(headers, HEADER_TRACE_ID, traceId);
        putIfAbsent(headers, HEADER_REQUEST_ID, requestId);
        putIfAbsent(headers, HEADER_TRACEPARENT, toTraceparent(traceId, spanId));
        return new KafkaContext(traceId, requestId, spanId, null, record.topic(),
                record.partition(), null, null, hashKey(record.key()), record.timestamp(), null, isDltTopic(record.topic()));
    }

    static KafkaContext fromSendArguments(Object[] args) {
        RequestContext req = AllStakClient.getRequestContext();
        String traceId = req != null && !isBlank(req.getTraceId()) ? req.getTraceId() : UUID.randomUUID().toString();
        String requestId = req != null && !isBlank(req.getRequestId()) ? req.getRequestId() : "kafka-" + UUID.randomUUID();
        String spanId = randomSpanId();
        String topic = null;
        Integer partition = null;
        Object key = null;
        if (args != null && args.length > 0) {
            if (args[0] instanceof Message<?> message) {
                Object headerTopic = message.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.TOPIC);
                if (headerTopic != null) topic = String.valueOf(headerTopic);
                key = message.getHeaders().get(org.springframework.kafka.support.KafkaHeaders.KEY);
            } else if (args[0] instanceof String t) topic = t;
            if (args.length > 1 && args[1] instanceof Integer p) {
                partition = p;
                if (args.length > 2) key = args[2];
            } else if (args.length > 2 && args[2] instanceof Integer p) {
                partition = p;
                if (args.length > 3) key = args[3];
            } else if (args.length > 1) {
                key = args[1];
            }
        }
        return new KafkaContext(traceId, requestId, spanId, null, topic, partition,
                null, null, hashKey(key), null, null, isDltTopic(topic));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Message<?> injectMessageHeaders(Message<?> message, KafkaContext ctx) {
        if (message == null) return null;
        MessageBuilder builder = MessageBuilder.fromMessage(message);
        if (!message.getHeaders().containsKey(HEADER_TRACE_ID)) builder.setHeader(HEADER_TRACE_ID, ctx.traceId());
        if (!message.getHeaders().containsKey(HEADER_REQUEST_ID)) builder.setHeader(HEADER_REQUEST_ID, ctx.requestId());
        if (!message.getHeaders().containsKey(HEADER_TRACEPARENT)) builder.setHeader(HEADER_TRACEPARENT, toTraceparent(ctx.traceId(), ctx.spanId()));
        return builder.build();
    }

    static void captureSpan(AllStakClient client, KafkaContext ctx, String operation, String description,
                            String status, long startMs, Throwable error) {
        try {
            long endMs = System.currentTimeMillis();
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("messaging.system", "kafka");
            tags.put("span.kind", operation.endsWith("consumer") ? "messaging.consumer" : "messaging.producer");
            if (!isBlank(ctx.topic())) tags.put("messaging.destination.name", ctx.topic());
            if (ctx.partition() != null) tags.put("messaging.kafka.partition", String.valueOf(ctx.partition()));
            if (ctx.offset() != null) tags.put("messaging.kafka.offset", String.valueOf(ctx.offset()));
            if (!isBlank(ctx.groupId())) tags.put("messaging.kafka.consumer_group", ctx.groupId());
            if (!isBlank(ctx.keyHash())) tags.put("messaging.kafka.key_hash", ctx.keyHash());
            if (ctx.timestamp() != null) tags.put("messaging.kafka.timestamp", String.valueOf(ctx.timestamp()));
            if (!isBlank(ctx.deliveryAttempt())) tags.put("messaging.kafka.delivery_attempt", ctx.deliveryAttempt());
            tags.put("messaging.kafka.dead_letter", String.valueOf(ctx.deadLetter()));
            tags.put("request.id", ctx.requestId());
            if (error != null) {
                tags.put("error.type", error.getClass().getName());
                if (error.getMessage() != null) tags.put("error.message", error.getMessage());
            }
            client.captureSpan(ctx.traceId(), ctx.spanId(), ctx.parentSpanId(), operation, description,
                    status, endMs - startMs, startMs, endMs,
                    client.getConfig().getServiceName(), client.getConfig().getEnvironment(), tags);
        } catch (Exception ignored) {
            // Kafka instrumentation must never affect producer/consumer flow.
        }
    }

    static RequestContext requestContext(KafkaContext ctx) {
        String path = !isBlank(ctx.topic()) ? ctx.topic() : "kafka";
        return RequestContext.of("KAFKA", path, "kafka", null, "spring-kafka", ctx.traceId(), ctx.requestId());
    }

    static Map<String, Object> metadata(KafkaContext ctx, String instrumentation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("instrumentation", instrumentation);
        metadata.put("messaging.system", "kafka");
        metadata.put("messaging.destination.name", ctx.topic());
        metadata.put("messaging.kafka.partition", ctx.partition());
        metadata.put("messaging.kafka.offset", ctx.offset());
        metadata.put("messaging.kafka.consumer_group", ctx.groupId());
        metadata.put("messaging.kafka.key_hash", ctx.keyHash());
        metadata.put("messaging.kafka.delivery_attempt", ctx.deliveryAttempt());
        metadata.put("messaging.kafka.dead_letter", ctx.deadLetter());
        metadata.put("requestId", ctx.requestId());
        return metadata;
    }

    private static String header(Headers headers, String name) {
        if (headers == null) return null;
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void putIfAbsent(Headers headers, String name, String value) {
        if (headers.lastHeader(name) == null) {
            headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String traceIdFromTraceparent(String traceparent) {
        if (isBlank(traceparent)) return null;
        String[] parts = traceparent.split("-");
        return parts.length >= 2 && parts[1].length() == 32 ? parts[1] : null;
    }

    private static String toTraceparent(String traceId, String spanId) {
        String normalizedTrace = traceId.replace("-", "");
        if (normalizedTrace.length() < 32) normalizedTrace = (normalizedTrace + "00000000000000000000000000000000").substring(0, 32);
        if (normalizedTrace.length() > 32) normalizedTrace = normalizedTrace.substring(0, 32);
        return "00-" + normalizedTrace + "-" + spanId + "-01";
    }

    private static String randomSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String hashKey(Object key) {
        if (key == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) out.append(String.format("%02x", hash[i]));
            return out.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static boolean isDltTopic(String topic) {
        if (isBlank(topic)) return false;
        String lowered = topic.toLowerCase();
        return lowered.endsWith(".dlt") || lowered.endsWith("-dlt") || lowered.endsWith(".dead-letter") || lowered.endsWith("-dead-letter");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record KafkaContext(String traceId, String requestId, String spanId, String parentSpanId,
                        String topic, Integer partition, Long offset, String groupId,
                        String keyHash, Long timestamp, String deliveryAttempt, boolean deadLetter) {}
}
