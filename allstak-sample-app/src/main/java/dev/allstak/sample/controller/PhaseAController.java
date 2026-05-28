package dev.allstak.sample.controller;

import dev.allstak.AllStak;
import dev.allstak.feedback.Attachment;
import dev.allstak.feedback.UserFeedback;
import dev.allstak.model.UserContext;
import dev.allstak.scope.Scopes;
import dev.allstak.tracing.TracePropagationDecider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sample endpoints exercising the Phase A APIs end-to-end. Each route
 * leaves observable footprints in the AllStak dashboard so a developer
 * can verify the new surface with a real Spring Boot app.
 */
@RestController
@RequestMapping("/phase-a")
public class PhaseAController {

    @GetMapping("/scope/with-scope")
    public Map<String, Object> withScopeExample() {
        Scopes.isolation().setTag("layer", "isolation");
        Map<String, Object> view = new LinkedHashMap<>();

        AllStak.withScope(cur -> {
            cur.setTag("layer", "current");           // overrides isolation
            cur.setUser(UserContext.ofId("sample-user-1"));
            // Capture happens *inside* the scope so the dashboard sees both tags.
            AllStak.captureException(new IllegalStateException("withScope demo — non-fatal"));
            view.put("merged.layer", "current");
            view.put("merged.user", "sample-user-1");
        });

        view.put("after.layer", Scopes.isolation().getTags().get("layer"));
        return view;
    }

    @GetMapping("/scope/with-isolation")
    public String withIsolationExample() {
        AllStak.withIsolationScope(s -> {
            s.setTag("background.job", "report-generator");
            AllStak.addBreadcrumb("job", "starting report generation");
            AllStak.captureException(new RuntimeException("withIsolationScope demo"));
        });
        return "isolation scope demo complete";
    }

    @GetMapping("/sessions/info")
    public Map<String, Object> sessionInfo() {
        // The single-mode session opened at AllStak.init is still alive — there
        // is no per-request session in server mode. We just expose what the
        // SDK knows so the demo can render it.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session.enabled", AllStak.getClient() != null
                && AllStak.getClient().getConfig().isEnableAutoSessionTracking());
        return out;
    }

    @PostMapping("/feedback")
    public String postFeedback(@RequestBody UserFeedbackBody body) {
        AllStak.captureFeedback(new UserFeedback(body.eventId, body.name, body.email, body.comments));
        return "feedback queued";
    }

    @GetMapping("/attachment/demo")
    public String attachmentDemo() {
        byte[] bytes = "AllStak attachment demo — text/plain".getBytes(StandardCharsets.UTF_8);
        AllStak.captureAttachment(new Attachment("demo.txt", "text/plain", bytes));
        return "attachment queued (size=" + bytes.length + ")";
    }

    @GetMapping("/propagation/decision")
    public Map<String, Object> propagationDecision() {
        TracePropagationDecider decider = AllStak.getClient().getConfig().getTracePropagationDecider();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("internal", decider.shouldPropagate("https://api.allstak.sa/v1/foo"));
        out.put("third-party", decider.shouldPropagate("https://third-party.example.com/x"));
        return out;
    }

    /** Small DTO so Spring can deserialize JSON bodies without an extra file. */
    public static final class UserFeedbackBody {
        public String eventId;
        public String name;
        public String email;
        public String comments;
    }
}
