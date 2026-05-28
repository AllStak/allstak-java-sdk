package dev.allstak.kafka;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakKafkaProducerInterceptorTest {

    @BeforeEach
    void setUp() {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test").environment("test").release("v0.0.1")
                .enableAutoSessionTracking(false).installUncaughtExceptionHandler(false)
                .build();
        AllStak.init(new AllStakClient(cfg, new HttpTransport("http://127.0.0.1:1", cfg.getApiKey())));
    }
    @AfterEach
    void tearDown() { AllStak.reset(); }

    @Test
    void onSend_addsTraceHeaders() {
        var rec = new ProducerRecord<Object, Object>("topic-a", "key", "value");
        var sent = new AllStakKafkaProducerInterceptor().onSend(rec);
        assertThat(sent.headers().lastHeader(AllStakKafkaProducerInterceptor.HEADER_TRACE_ID)).isNotNull();
        assertThat(sent.headers().lastHeader(AllStakKafkaProducerInterceptor.HEADER_SPAN_ID)).isNotNull();
    }

    @Test
    void onSend_doesNotOverwriteExistingTraceHeader() {
        var rec = new ProducerRecord<Object, Object>("topic-a", null, null, "k", "v");
        rec.headers().add(AllStakKafkaProducerInterceptor.HEADER_TRACE_ID, "preset".getBytes());
        var sent = new AllStakKafkaProducerInterceptor().onSend(rec);
        assertThat(new String(sent.headers().lastHeader(AllStakKafkaProducerInterceptor.HEADER_TRACE_ID).value()))
                .isEqualTo("preset");
    }
}
