package dev.kitsune.client.event;

/**
 * Fired before a chat message (or slash command) is sent to the server.
 * Handlers can:
 * <ul>
 *   <li>Drop it entirely by setting {@link #cancelled} to true</li>
 *   <li>Rewrite it by calling {@link #setMessage(String)} — useful for
 *       aliases and command rewrites</li>
 * </ul>
 * {@link #isCommand} distinguishes {@code /cmd} from chat lines.
 */
public final class ChatSendEvent {
    private String message;
    public final boolean isCommand;
    public boolean cancelled;

    public ChatSendEvent(String message, boolean isCommand) {
        this.message = message;
        this.isCommand = isCommand;
    }

    public String getMessage() { return message; }
    public void setMessage(String newMessage) { this.message = newMessage; }

    // Back-compat: existing code reads event.message directly.
    public String message() { return message; }
}
