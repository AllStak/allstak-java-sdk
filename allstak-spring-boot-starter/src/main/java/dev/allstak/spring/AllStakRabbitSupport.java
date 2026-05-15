package dev.allstak.spring;

import dev.allstak.AllStakClient;
import dev.allstak.model.RequestContext;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class AllStakRabbitSupport {
    static final String HEADER_TRACE_ID = "x-allstak-trace-id";
    static final String HEADER_REQUEST_ID = "x-allstak-request-id";
    static final String HEADER_PARENT_SPAN_ID = "x-allstak-parent-span-id";
    static final String HEADER_TRACEPARENT = "traceparent";

    private AllStakRabbitSupport() {}

    static RabbitContext fromMessage(Message message, String fallbackQueue) {
        MessageProperties props = message != null ? message.getMessageProperties() : null;
        Map<String, Object> headers = props != null ? props.getHeaders() : Map.of();
        String traceId = stringHeader(headers, HEADER_TRACE_ID);
        if (isBlank(traceId)) traceId = traceIdFromTraceparent(stringHeader(headers, HEADER_TRACEPARENT));
        if (isBlank(traceId)) traceId = UUID.randomUUID().toString();

        String requestId = stringHeader(headers, HEADER_REQUEST_ID);
        if (isBlank(requestId) && props != null) requestId = props.getMessageId();
        if (isBlank(requestId)) requestId = "msg-" + UUID.randomUUID();

        String parentSpanId = stringHeader(headers, HEADER_PARENT_SPAN_ID);
        String spanId = randomSpanId();
        String exchange = props != null ? props.getReceivedExchange() : null;
        String routingKey = props != null ? props.getReceivedRoutingKey() : null;
        String queue = !isBlank(fallbackQueue) ? fallbackQueue : routingKey;
        String deliveryTag = props != null ? String.valueOf(props.getDeliveryTag()) : null;
        boolean redelivered = props != null && props.isRedelivered();
        Object xDeath = headers.get("x-death");
        String deathCount = deathCount(xDeath);
        String deadLetterExchange = stringHeader(headers, "x-dead-letter-exchange");
        String deadLetterRoutingKey = stringHeader(headers, "x-dead-letter-routing-key");
        return new RabbitContext(traceId, requestId, spanId, parentSpanId, exchange, routingKey, queue,
                deliveryTag, redelivered, deathCount, deadLetterExchange, deadLetterRoutingKey);
    }

    static RabbitContext injectProducerHeaders(Message message) {
        MessageProperties props = message.getMessageProperties();
        Map<String, Object> headers = props.getHeaders();
        RequestContext req = AllStakClient.getRequestContext();
        String traceId = req != null && !isBlank(req.getTraceId()) ? req.getTraceId() : UUID.randomUUID().toString();
        String requestId = req != null && !isBlank(req.getRequestId()) ? req.getRequestId() : "msg-" + UUID.randomUUID();
        String parentSpanId = null;
        String spanId = randomSpanId();
        headers.putIfAbsent(HEADER_TRACE_ID, traceId);
        headers.putIfAbsent(HEADER_REQUEST_ID, requestId);
        if (!isBlank(parentSpanId)) headers.putIfAbsent(HEADER_PARENT_SPAN_ID, parentSpanId);
        headers.putIfAbsent(HEADER_TRACEPARENT, toTraceparent(traceId, spanId));
        if (isBlank(props.getMessageId())) props.setMessageId(requestId);
        return new RabbitContext(traceId, requestId, spanId, parentSpanId, null, null, null, null, false, null, null, null);
    }

    static void captureSpan(AllStakClient client, RabbitContext ctx, String operation, String description,
                            String status, long startMs, Throwable error) {
        try {
            long endMs = System.currentTimeMillis();
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("messaging.system", "rabbitmq");
            tags.put("span.kind", operation.endsWith("consumer") ? "messaging.consumer" : "messaging.producer");
            if (!isBlank(ctx.exchange())) tags.put("messaging.rabbitmq.exchange", ctx.exchange());
            if (!isBlank(ctx.routingKey())) tags.put("messaging.rabbitmq.routing_key", ctx.routingKey());
            if (!isBlank(ctx.queue())) tags.put("messaging.destination", ctx.queue());
            if (!isBlank(ctx.deliveryTag())) tags.put("messaging.rabbitmq.delivery_tag", ctx.deliveryTag());
            tags.put("messaging.rabbitmq.redelivered", String.valueOf(ctx.redelivered()));
            if (!isBlank(ctx.xDeathCount())) tags.put("messaging.rabbitmq.x_death_count", ctx.xDeathCount());
            if (!isBlank(ctx.deadLetterExchange())) tags.put("messaging.rabbitmq.dead_letter_exchange", ctx.deadLetterExchange());
            if (!isBlank(ctx.deadLetterRoutingKey())) tags.put("messaging.rabbitmq.dead_letter_routing_key", ctx.deadLetterRoutingKey());
            tags.put("request.id", ctx.requestId());
            if (error != null) {
                tags.put("error.type", error.getClass().getName());
                if (error.getMessage() != null) tags.put("error.message", error.getMessage());
            }
            client.captureSpan(ctx.traceId(), ctx.spanId(), ctx.parentSpanId(), operation, description,
                    status, endMs - startMs, startMs, endMs,
                    client.getConfig().getServiceName(), client.getConfig().getEnvironment(), tags);
        } catch (Exception ignored) {
            // Messaging instrumentation must never affect the app path.
        }
    }

    static RequestContext requestContext(RabbitContext ctx) {
        String path = !isBlank(ctx.queue()) ? ctx.queue() : !isBlank(ctx.routingKey()) ? ctx.routingKey() : "rabbitmq";
        String host = !isBlank(ctx.exchange()) ? ctx.exchange() : "rabbitmq";
        return RequestContext.of("RABBIT", path, host, null, "spring-amqp", ctx.traceId(), ctx.requestId());
    }

    private static String stringHeader(Map<String, Object> headers, String key) {
        Object value = headers.get(key);
        return value != null ? String.valueOf(value) : null;
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record RabbitContext(String traceId, String requestId, String spanId, String parentSpanId,
                         String exchange, String routingKey, String queue, String deliveryTag,
                         boolean redelivered, String xDeathCount,
                         String deadLetterExchange, String deadLetterRoutingKey) {}

    private static String deathCount(Object xDeath) {
        if (xDeath instanceof java.util.List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object count = first.get("count");
            return count != null ? String.valueOf(count) : null;
        }
        return null;
    }
}
