package dev.allstak.kotlin_coroutines;

import dev.allstak.scope.Scopes;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeSnapshotTest {

    @AfterEach
    void tearDown() { Scopes.clear(); }

    @Test
    void captureAndRestore_carriesTagsAcrossThreads() throws Exception {
        Scopes.isolation().setTag("origin", "main");
        ScopeSnapshot snap = ScopeSnapshot.capture();

        Thread worker = new Thread(() -> {
            // Child thread starts with an inherited (then copied) scope.
            // Force-clear to simulate a worker pool reusing a "dirty" scope.
            Scopes.isolation().clear();
            assertThat(Scopes.isolation().getTags()).doesNotContainKey("origin");
            snap.restore();
            assertThat(Scopes.isolation().getTags().get("origin")).isEqualTo("main");
        });
        worker.start();
        worker.join();
    }
}
