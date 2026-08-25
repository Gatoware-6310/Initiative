package xyz.gatoware.initiative.devices;

/**
 * One authenticated, persistent connection from a Node to the Initiative Core.
 * It can be backed by WebSocket or raw TCP without changing the Node API.
 */
public interface NodeConnection {
    boolean isOpen();

    void sendMessage(String message);

    String executePython(String pythonSource, Object... arguments);
}
