package dev.allstak.transport;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTransportDiagnosticsTest {

    @Test
    void diagnosticsCountAcceptedPermanentRateLimitAndRetry() {
        ScriptedHttpClient http = new ScriptedHttpClient(List.of(
                response(202, "ok"),
                response(400, "bad"),
                response(429, "rate-limited"),
                response(202, "ok-after-retry")));
        HttpTransport transport = new HttpTransport("https://fake.allstak.test", "ask_live_diag", http);

        assertThat(transport.sendWithResult("/ingest/v1/errors", java.util.Map.of("message", "ok")))
                .isEqualTo(SendResult.ACCEPTED);
        assertThat(transport.sendWithResult("/ingest/v1/errors", java.util.Map.of("message", "bad")))
                .isEqualTo(SendResult.PERMANENT);
        assertThat(transport.sendWithResult("/ingest/v1/errors", java.util.Map.of("message", "retry")))
                .isEqualTo(SendResult.ACCEPTED);

        TransportDiagnostics diagnostics = transport.getDiagnostics();
        assertThat(diagnostics.getEventsCaptured()).isEqualTo(3);
        assertThat(diagnostics.getEventsSent()).isEqualTo(2);
        assertThat(diagnostics.getEventsFailed()).isEqualTo(1);
        assertThat(diagnostics.getEventsDropped()).isEqualTo(1);
        assertThat(diagnostics.getRateLimitedCount()).isEqualTo(1);
        assertThat(diagnostics.getRetryAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(diagnostics.isDisabled()).isFalse();
    }

    @Test
    void tinyPayloadIsSentUncompressedAndCounted() {
        ScriptedHttpClient http = new ScriptedHttpClient(List.of(response(202, "ok")));
        HttpTransport transport = new HttpTransport("https://fake.allstak.test", "ask_live_diag", http);

        assertThat(transport.sendWithResult("/ingest/v1/logs", java.util.Map.of("message", "hi")))
                .isEqualTo(SendResult.ACCEPTED);

        CapturedRequest request = http.requests().get(0);
        assertThat(request.header("Content-Encoding")).isEmpty();
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).contains("\"message\":\"hi\"");
        TransportDiagnostics diagnostics = transport.getDiagnostics();
        assertThat(diagnostics.getUncompressedPayloads()).isEqualTo(1);
        assertThat(diagnostics.getCompressedPayloads()).isZero();
        assertThat(diagnostics.getCompressionBytesSaved()).isZero();
    }

    @Test
    void largePayloadIsGzippedWhenSmallerAndCounted() throws Exception {
        ScriptedHttpClient http = new ScriptedHttpClient(List.of(response(202, "ok")));
        HttpTransport transport = new HttpTransport("https://fake.allstak.test", "ask_live_diag", http);
        String message = "x".repeat(8_000);

        assertThat(transport.sendWithResult("/ingest/v1/errors", java.util.Map.of("message", message)))
                .isEqualTo(SendResult.ACCEPTED);

        CapturedRequest request = http.requests().get(0);
        assertThat(request.header("Content-Encoding")).contains("gzip");
        assertThat(gunzip(request.body())).contains(message);
        TransportDiagnostics diagnostics = transport.getDiagnostics();
        assertThat(diagnostics.getCompressedPayloads()).isEqualTo(1);
        assertThat(diagnostics.getUncompressedPayloads()).isZero();
        assertThat(diagnostics.getCompressionBytesSaved()).isPositive();
    }

    private static HttpResponse<String> response(int status, String body) {
        return new SimpleResponse(status, body);
    }

    private static String gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record CapturedRequest(HttpRequest request, byte[] body) {
        Optional<String> header(String name) {
            return request.headers().firstValue(name);
        }
    }

    private static final class ScriptedHttpClient extends HttpClient {
        private final Queue<HttpResponse<String>> responses;
        private final List<CapturedRequest> requests = new java.util.ArrayList<>();

        ScriptedHttpClient(List<HttpResponse<String>> responses) {
            this.responses = new java.util.ArrayDeque<>(responses);
        }

        List<CapturedRequest> requests() {
            return requests;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            HttpResponse<String> response = responses.isEmpty()
                    ? response(202, "ok")
                    : responses.remove();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (request.bodyPublisher().isPresent()) {
                // Drain the publisher so serialization and request-body paths are exercised.
                request.bodyPublisher().get().subscribe(new Flow.Subscriber<>() {
                    @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                    @Override public void onNext(ByteBuffer item) {
                        byte[] chunk = new byte[item.remaining()];
                        item.get(chunk);
                        body.writeBytes(chunk);
                    }
                    @Override public void onError(Throwable throwable) {}
                    @Override public void onComplete() {}
                });
            }
            requests.add(new CapturedRequest(request, body.toByteArray()));
            return (HttpResponse<T>) response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private static final class SimpleResponse implements HttpResponse<String> {
        private static final AtomicInteger SEQ = new AtomicInteger();
        private final int status;
        private final String body;

        SimpleResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
        @Override public String body() { return body; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://fake.allstak.test/" + SEQ.incrementAndGet()); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
