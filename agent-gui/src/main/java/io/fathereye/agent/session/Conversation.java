package io.fathereye.agent.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable conversation record. Mutable so we can append turns without
 * copying the message list every send. Persistence is via
 * {@link ConversationStore#save}.
 */
public final class Conversation {

    public record Message(String role, String text) {
        public static final String USER = "user";
        public static final String ASSISTANT = "assistant";
        public static final String ERROR = "error";
    }

    private final String id;
    private String title;
    private final long createdAt;
    private long updatedAt;
    private String cwd;
    private String model;
    private final List<Message> messages;

    public Conversation(String id, String title, long createdAt, long updatedAt,
                        String cwd, String model, List<Message> messages) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.cwd = cwd;
        this.model = model;
        this.messages = messages == null ? new ArrayList<>() : messages;
    }

    public String id() { return id; }
    public String title() { return title; }
    public void setTitle(String s) { this.title = s; this.updatedAt = System.currentTimeMillis(); }
    public long createdAt() { return createdAt; }
    public long updatedAt() { return updatedAt; }
    public String cwd() { return cwd; }
    public void setCwd(String s) { this.cwd = s; this.updatedAt = System.currentTimeMillis(); }
    public String model() { return model; }
    public void setModel(String s) { this.model = s; this.updatedAt = System.currentTimeMillis(); }
    public List<Message> messages() { return messages; }

    public void addUser(String text) {
        messages.add(new Message(Message.USER, text));
        updatedAt = System.currentTimeMillis();
        if ("New conversation".equals(title) && !text.isBlank()) {
            // First user message becomes the conversation title.
            title = text.length() > 60 ? text.substring(0, 57).replace('\n', ' ') + "…"
                                       : text.replace('\n', ' ');
        }
    }
    public void addAssistant(String text) {
        if (messages.isEmpty() || !messages.get(messages.size() - 1).role().equals(Message.ASSISTANT)) {
            messages.add(new Message(Message.ASSISTANT, text));
        } else {
            // Append onto the last assistant block. Records are immutable,
            // so create a replacement.
            Message last = messages.remove(messages.size() - 1);
            messages.add(new Message(Message.ASSISTANT, last.text() + "\n\n" + text));
        }
        updatedAt = System.currentTimeMillis();
    }
    public void addError(String text) {
        messages.add(new Message(Message.ERROR, text));
        updatedAt = System.currentTimeMillis();
    }
}
