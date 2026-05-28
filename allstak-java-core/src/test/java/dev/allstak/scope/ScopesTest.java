package dev.allstak.scope;

import dev.allstak.model.Breadcrumb;
import dev.allstak.model.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ScopesTest {

    @AfterEach
    void tearDown() {
        Scopes.clear();
    }

    @Test
    void global_isolation_current_layeringPrecedence() {
        Scopes.global().setTag("region", "global-region");
        Scopes.global().setUser(UserContext.ofId("global-user"));

        Scopes.isolation().setTag("request.id", "iso-1");
        Scopes.isolation().setUser(UserContext.ofId("iso-user"));

        Scopes.withScope(cur -> {
            cur.setTag("op", "checkout");
            cur.setUser(UserContext.ofId("cur-user"));

            MergedScope merged = Scopes.mergedForCapture();
            // User picks the most-specific non-null.
            assertEquals("cur-user", merged.user().getId());
            // Tags are unioned with Current overwriting on conflicts.
            assertEquals("global-region", merged.tags().get("region"));
            assertEquals("iso-1", merged.tags().get("request.id"));
            assertEquals("checkout", merged.tags().get("op"));
        });

        // Current popped — back to isolation winning.
        MergedScope afterPop = Scopes.mergedForCapture();
        assertEquals("iso-user", afterPop.user().getId());
        assertNull(afterPop.tags().get("op"));
    }

    @Test
    void withScope_doesNotLeakIntoIsolation() {
        Scopes.isolation().setTag("base", "1");
        Scopes.withScope(cur -> cur.setTag("ephemeral", "x"));
        assertNull(Scopes.isolation().getTags().get("ephemeral"));
        assertEquals("1", Scopes.isolation().getTags().get("base"));
    }

    @Test
    void withIsolationScope_freshScopeReplacedAndRestored() {
        Scopes.isolation().setTag("outer", "yes");
        Scopes.withIsolationScope(iso -> {
            assertNull(iso.getTags().get("outer"));
            iso.setTag("inner", "yes");
            assertEquals("yes", Scopes.mergedForCapture().tags().get("inner"));
        });
        assertEquals("yes", Scopes.isolation().getTags().get("outer"));
        assertNull(Scopes.isolation().getTags().get("inner"));
    }

    @Test
    void breadcrumbs_mergeAcrossLayers_globalFirstThenIsoThenCurrent() {
        Scopes.global().addBreadcrumb(new Breadcrumb("default", "global-crumb", "info", null));
        Scopes.isolation().addBreadcrumb(new Breadcrumb("default", "iso-crumb", "info", null));
        Scopes.withScope(cur -> {
            cur.addBreadcrumb(new Breadcrumb("default", "cur-crumb", "info", null));
            List<Breadcrumb> merged = Scopes.mergedForCapture().breadcrumbs();
            assertEquals(3, merged.size());
            assertEquals("global-crumb", merged.get(0).getMessage());
            assertEquals("iso-crumb",    merged.get(1).getMessage());
            assertEquals("cur-crumb",    merged.get(2).getMessage());
        });
    }

    @Test
    void scope_isPerThread() throws Exception {
        Scopes.isolation().setTag("main-thread", "yes");
        AtomicReference<String> seenInChild = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            // Inheritable: child observes the parent's tag at fork time...
            seenInChild.set(Scopes.isolation().getTags().get("main-thread"));
            // ...and can mutate without affecting the parent.
            Scopes.isolation().setTag("child-only", "1");
            done.countDown();
        });
        t.start();
        done.await();

        assertEquals("yes", seenInChild.get());
        // Mutations in the child do NOT bleed back into the parent thread.
        assertNull(Scopes.isolation().getTags().get("child-only"));
    }

    @Test
    void breadcrumbBounding_dropsOldestPastLimit() {
        Scope s = new Scope(3);
        for (int i = 0; i < 5; i++) {
            s.addBreadcrumb(new Breadcrumb("default", "msg-" + i, "info", null));
        }
        List<Breadcrumb> all = s.getBreadcrumbs();
        assertEquals(3, all.size());
        assertEquals("msg-2", all.get(0).getMessage());
        assertEquals("msg-4", all.get(2).getMessage());
    }

    @Test
    void configureScope_writesToActiveLayer() {
        Scopes.configureScope(s -> s.setTag("via-configure", "y"));
        // No Current pushed → wrote to isolation.
        assertEquals("y", Scopes.isolation().getTags().get("via-configure"));

        Scopes.withScope(cur -> {
            Scopes.configureScope(s -> s.setTag("inner-cfg", "z"));
            assertEquals("z", cur.getTags().get("inner-cfg"));
            // Isolation untouched by the inner write.
            assertNull(Scopes.isolation().getTags().get("inner-cfg"));
        });
    }
}
