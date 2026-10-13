package com.cowatch.client.model;

/**
 * Whether mpv starts playing as soon as cowatch launches it, or opens paused.
 *
 * <p>Opening paused is what keeps a room together: everyone loads the file,
 * then one person presses play and the server starts every player at once.
 * Watching alone there is no one to wait for, so a paused start only means an
 * extra click.
 *
 * <p>The cowatch server does not tell a client who else is in the room, so
 * "alone" is judged by whether this client is connected to a server at all.
 */
public enum StartBehavior {

    /** Play at once when not connected to a server; open paused when connected. */
    AUTO("Play unless in a room"),

    /** Always play at once, even in a room. */
    PLAY("Always play"),

    /** Always open paused and wait for play. */
    PAUSED("Always start paused");

    public final String label;

    StartBehavior(String label) {
        this.label = label;
    }

    /**
     * Whether a launch should open paused.
     *
     * @param inRoom true when this client is connected to a cowatch server
     */
    public boolean startPaused(boolean inRoom) {
        return switch (this) {
            case AUTO   -> inRoom;
            case PLAY   -> false;
            case PAUSED -> true;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
