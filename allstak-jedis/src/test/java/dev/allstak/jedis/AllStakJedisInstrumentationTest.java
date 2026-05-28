package dev.allstak.jedis;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

class AllStakJedisInstrumentationTest {

    @BeforeEach
    void setUp() {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test").environment("test").release("v0.0.1")
                .enableAutoSessionTracking(false).installUncaughtExceptionHandler(false).build();
        AllStak.init(new AllStakClient(cfg, new HttpTransport("http://127.0.0.1:1", cfg.getApiKey())));
    }
    @AfterEach void tearDown() { AllStak.reset(); }

    @Test
    void timed_returnsValueAndDoesNotThrow() throws Exception {
        String value = AllStakJedisInstrumentation.timed("GET", () -> "hello");
        assertThat(value).isEqualTo("hello");
    }

    @Test
    void timed_rethrowsAndStillRecords() {
        assertThatThrownBy(() ->
                AllStakJedisInstrumentation.timed("SET", () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }
}
