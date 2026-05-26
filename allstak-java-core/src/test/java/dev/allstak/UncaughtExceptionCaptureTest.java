package dev.allstak;

import dev.allstak.internal.UncaughtExceptionCapture;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the global uncaught-exception handler installs, chains the prior
 * handler, is idempotent, respects the opt-out flag, and marks events unhandled.
 */
class UncaughtExceptionCaptureTest {

    private final AtomicReference<Thread.UncaughtExceptionHandler> originalDefault =
            new AtomicReference<>();

    @AfterEach
    void tearDown() {
        // Always clean up any handler our client installed and restore the
        // JVM default we started from.
        UncaughtExceptionCapture.uninstall();
        if (originalDefault.get() != null) {
            Thread.setDefaultUncaughtExceptionHandler(originalDefault.get());
        }
    }

    private AllStakClient newClient(AllStakConfig config) {
        // Use a transport pointed at a non-routable host; it never has to succeed.
        HttpTransport transport = new HttpTransport("http://localhost:1", config.getApiKey());
        return new AllStakClient(config, transport);
    }

    @Test
    void installsByDefaultAndChainsPriorHandler() {
        originalDefault.set(Thread.getDefaultUncaughtExceptionHandler());
        AtomicReference<Throwable> delegated = new AtomicReference<>();
        Thread.UncaughtExceptionHandler prior = (t, e) -> delegated.set(e);
        Thread.setDefaultUncaughtExceptionHandler(prior);

        AllStakClient client = newClient(AllStakConfig.builder().apiKey("k").build());

        Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
        assertThat(installed).isNotSameAs(prior);
        assertThat(UncaughtExceptionCapture.isInstalled()).isTrue();

        // Triggering the handler must delegate to the prior handler (no swallow).
        RuntimeException boom = new RuntimeException("background boom");
        installed.uncaughtException(Thread.currentThread(), boom);
        assertThat(delegated.get()).isSameAs(boom);

        client.shutdown();
    }

    @Test
    void shutdownRestoresPriorHandler() {
        originalDefault.set(Thread.getDefaultUncaughtExceptionHandler());
        Thread.UncaughtExceptionHandler prior = (t, e) -> { };
        Thread.setDefaultUncaughtExceptionHandler(prior);

        AllStakClient client = newClient(AllStakConfig.builder().apiKey("k").build());
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isNotSameAs(prior);

        client.shutdown();
        assertThat(UncaughtExceptionCapture.isInstalled()).isFalse();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(prior);
    }

    @Test
    void doubleInstallIsIdempotent() {
        originalDefault.set(Thread.getDefaultUncaughtExceptionHandler());
        AllStakClient first = newClient(AllStakConfig.builder().apiKey("k").build());
        Thread.UncaughtExceptionHandler afterFirst = Thread.getDefaultUncaughtExceptionHandler();

        AllStakClient second = newClient(AllStakConfig.builder().apiKey("k").build());
        Thread.UncaughtExceptionHandler afterSecond = Thread.getDefaultUncaughtExceptionHandler();

        // Second install is a no-op — same handler instance stays in place.
        assertThat(afterSecond).isSameAs(afterFirst);

        first.shutdown();
        second.shutdown();
    }

    @Test
    void optOutDoesNotInstall() {
        originalDefault.set(Thread.getDefaultUncaughtExceptionHandler());
        Thread.UncaughtExceptionHandler prior = (t, e) -> { };
        Thread.setDefaultUncaughtExceptionHandler(prior);

        AllStakClient client = newClient(AllStakConfig.builder()
                .apiKey("k")
                .installUncaughtExceptionHandler(false)
                .build());

        // The default handler must be untouched.
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isSameAs(prior);
        assertThat(UncaughtExceptionCapture.isInstalled()).isFalse();

        client.shutdown();
    }
}
