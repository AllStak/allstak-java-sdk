package dev.allstak.scope;

import dev.allstak.model.Breadcrumb;
import dev.allstak.model.UserContext;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of Global + Isolation + Current merged for a single
 * capture call. Scalars take the most-specific non-null value (current →
 * isolation → global). Maps/lists are unioned with the more-specific layer
 * overwriting keys (for maps) and appended after (for lists, ordered
 * Global, Isolation, Current).
 *
 * <p>Producers read a single snapshot to avoid lock churn during event
 * construction; consumers may not mutate it.
 */
public final class MergedScope {

    private final UserContext user;
    private final String level;
    private final String transaction;
    private final List<String> fingerprint;
    private final Map<String, String> tags;
    private final Map<String, Object> contexts;
    private final Map<String, Object> extras;
    private final List<Breadcrumb> breadcrumbs;

    MergedScope(UserContext user, String level, String transaction, List<String> fingerprint,
                Map<String, String> tags, Map<String, Object> contexts,
                Map<String, Object> extras, List<Breadcrumb> breadcrumbs) {
        this.user = user;
        this.level = level;
        this.transaction = transaction;
        this.fingerprint = fingerprint;
        this.tags = tags;
        this.contexts = contexts;
        this.extras = extras;
        this.breadcrumbs = breadcrumbs;
    }

    public UserContext user() { return user; }
    public String level() { return level; }
    public String transaction() { return transaction; }
    public List<String> fingerprint() { return fingerprint; }
    public Map<String, String> tags() { return tags; }
    public Map<String, Object> contexts() { return contexts; }
    public Map<String, Object> extras() { return extras; }
    public List<Breadcrumb> breadcrumbs() { return breadcrumbs; }
}
