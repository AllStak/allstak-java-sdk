package dev.allstak.feedback;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * End-user feedback associated to a previously captured error event. The
 * dashboard displays it inline on the issue page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class UserFeedback {

    private final String eventId;
    private final String name;
    private final String email;
    private final String comments;

    public UserFeedback(String eventId, String name, String email, String comments) {
        this.eventId = eventId;
        this.name = name;
        this.email = email;
        this.comments = comments;
    }

    public String getEventId()  { return eventId;  }
    public String getName()     { return name;     }
    public String getEmail()    { return email;    }
    public String getComments() { return comments; }
}
