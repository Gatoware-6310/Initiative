package xyz.gatoware.devices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import xyz.gatoware.actions.Action;

/** A machine or embedded device running Initiative Node software. */
public class Node extends Device {
    private final String id;
    private final String address;
    private final List<String> capabilities;
    private final List<Action> actions;
    private volatile NodeConnection connection;

    public Node(final String id, final String address) {
        this(id, id, address, Collections.<String>emptyList());
    }

    public Node(final String name, final String id, final String address,
            final List<String> capabilities) {
        super(name, DeviceType.NODE);
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("A node ID is required.");
        }
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("A node address is required.");
        }
        this.id = id;
        this.address = address;
        this.capabilities = new ArrayList<String>(
                Objects.requireNonNull(capabilities, "Capabilities are required."));
        this.actions = new ArrayList<Action>();
    }

    public String getId() {
        return id;
    }

    public String getAddress() {
        return address;
    }

    /** Reports whether the Core currently holds an open, persistent connection. */
    public boolean ping() {
        final NodeConnection activeConnection = connection;
        return activeConnection != null && activeConnection.isOpen();
    }

    /** Sends a predefined message through the node's existing connection. */
    public void sendMessage(final String message) {
        if (message == null) {
            throw new IllegalArgumentException("A message is required.");
        }
        requireConnection().sendMessage(message);
    }

    /** Sends Python source to the node and returns the output it reports. */
    public String sendRemoteScript(final String pythonSource, final Object... arguments) {
        return requireConnection().executePython(pythonSource, arguments);
    }

    /**
     * Attaches the already-established WebSocket or TCP session for this Node.
     * Core networking infrastructure calls this after node authentication.
     */
    public void attachConnection(final NodeConnection connection) {
        this.connection = Objects.requireNonNull(connection, "A node connection is required.");
    }

    /** Removes a connection only when it is still the Node's active session. */
    public void detachConnection(final NodeConnection connection) {
        if (this.connection == connection) {
            this.connection = null;
        }
    }

    public List<String> getCapabilities() {
        return Collections.unmodifiableList(capabilities);
    }

    /** Adds an action discovered or configured for this node. */
    public void addAction(final Action action) {
        actions.add(Objects.requireNonNull(action, "An action is required."));
    }

    @Override
    public Object status() {
        final Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("id", id);
        state.put("address", address);
        state.put("reachable", Boolean.valueOf(ping()));
        state.put("capabilities", getCapabilities());
        return Collections.unmodifiableMap(state);
    }

    @Override
    public List<Action> listActions() {
        return Collections.unmodifiableList(new ArrayList<Action>(actions));
    }

    @Override
    public String executeAction(final Action action, final Object... arguments) {
        verifyTarget(action);
        action.validateArguments(arguments);

        switch (action.getActionType()) {
            case MESSAGE:
                sendMessage(formatMessage(action.getContent(), arguments));
                return "";
            case REMOTE_SCRIPT:
                return sendRemoteScript(action.getContent(), arguments);
            case LOCAL_SCRIPT:
                throw new UnsupportedOperationException(
                        "Nodes cannot execute LOCAL_SCRIPT actions.");
            default:
                throw new IllegalArgumentException("Unsupported action type.");
        }
    }

    private NodeConnection requireConnection() {
        final NodeConnection activeConnection = connection;
        if (activeConnection == null || !activeConnection.isOpen()) {
            throw new IllegalStateException("Node '" + id + "' has no active connection.");
        }
        return activeConnection;
    }

    private String formatMessage(final String message, final Object... arguments) {
        return arguments.length == 0 ? message : String.format(message, arguments);
    }

    private void verifyTarget(final Action action) {
        if (action == null) {
            throw new IllegalArgumentException("An action is required.");
        }
        if (action.getTarget() != this) {
            throw new IllegalArgumentException("The action does not target this device.");
        }
    }
}
