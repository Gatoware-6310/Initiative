package xyz.gatoware.initiative.devices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;

import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.actions.ActionArgument;
import xyz.gatoware.initiative.actions.ActionTypes;

public class Node extends Device {
    private final String ip;
    private final LinkedBlockingQueue<String> replies = new LinkedBlockingQueue<String>();
    private volatile WebSocket connection;
    private volatile CountDownLatch connectionReady = new CountDownLatch(1);

    public Node(final String name, final String ip) {
        super(name, DeviceType.NODE);
        if (ip == null || ip.trim().isEmpty()) {
            throw new IllegalArgumentException("A node IP is required.");
        }
        this.ip = ip;
    }

    public String getIp() {
        return ip;
    }

    public boolean isConnected() {
        final WebSocket activeConnection = connection;
        return activeConnection != null && activeConnection.isOpen();
    }

    public void prepareConnection() {
        connectionReady = new CountDownLatch(1);
    }

    public boolean awaitConnection() {
        try {
            return connectionReady.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while connecting node '" + getName() + ".", exception);
        }
    }

    public void connect(final WebSocket connection) {
        this.connection = Objects.requireNonNull(connection, "A node connection is required.");
        connectionReady.countDown();
    }

    public void disconnect(final WebSocket connection) {
        if (this.connection == connection) {
            this.connection = null;
        }
    }

    public void reply(final String message) {
        replies.offer(message);
    }

    @Override
    public String status() {
        return request("STATUS").trim();
    }

    @Override
    public List<Action> listActions() {
        return parseCapabilities(request("CAPABILITIES"));
    }

    @Override
    public String executeAction(final Action action, final Object... arguments) {
        verifyTarget(action);
        action.validateArguments(arguments);

        if (action.getActionType() == ActionTypes.REMOTE_SCRIPT) {
            return request("PYTHON " + action.getContent());
        }
        if (action.getActionType() != ActionTypes.MESSAGE) {
            throw new UnsupportedOperationException("Nodes execute MESSAGE and REMOTE_SCRIPT actions.");
        }

        final StringBuilder message = new StringBuilder(action.getContent());
        for (final Object argument : arguments) {
            message.append(' ').append(argument);
        }
        return request(message.toString());
    }

    public String executePython(final String source) {
        if(source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("Python source is required.");
        }
        return request("PYTHON " + source);
    }

    private synchronized String request(final String message) {
        final WebSocket activeConnection = connection;
        if (activeConnection == null || !activeConnection.isOpen()) {
            throw new IllegalStateException("Node '" + getName() + "' is not connected.");
        }
        replies.clear();
        activeConnection.send(message);
        try {
            final String reply = replies.poll(5, TimeUnit.SECONDS);
            if (reply == null) {
                throw new IllegalStateException("Node '" + getName() + "' did not reply to " + message + ".");
            }
            return reply;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for node '" + getName() + ".", exception);
        }
    }

    private List<Action> parseCapabilities(final String output) {
        final List<Action> actions = new ArrayList<Action>();
        for (final String rawLine : output.split("\\R")) {
            final String line = rawLine.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                actions.add(parseCapability(line));
            }
        }
        return Collections.unmodifiableList(actions);
    }

    private Action parseCapability(final String line) {
        final String[] fields = line.split("\\t", -1);
        final String command = fields[0].trim();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("A capability command is required.");
        }

        final List<ActionArgument> arguments = new ArrayList<ActionArgument>();
        for (int index = 1; index < fields.length; index++) {
            arguments.add(parseArgument(fields[index].trim()));
        }
        return Action.message(command, this, command, arguments);
    }

    private ActionArgument parseArgument(final String declaration) {
        final String[] fields = declaration.split(":", -1);
        if (fields.length < 2 || fields[0].trim().isEmpty() || fields[1].trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid capability argument '" + declaration + "'.");
        }

        final String name = fields[0].trim();
        final Class<?> type = resolveType(fields[1].trim());
        if (type != Integer.class) {
            if (fields.length != 2) {
                throw new IllegalArgumentException("Only Integer capability arguments may declare a range.");
            }
            return new ActionArgument(name, type);
        }
        if (fields.length != 4) {
            throw new IllegalArgumentException("Integer capability argument '" + name
                    + "' must declare minimum and maximum values.");
        }
        try {
            return new ActionArgument(name, Integer.valueOf(fields[2].trim()),
                    Integer.valueOf(fields[3].trim()));
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid Integer range in '" + declaration + "'.", exception);
        }
    }

    private Class<?> resolveType(final String name) {
        switch (Objects.requireNonNull(name).toLowerCase(Locale.ROOT)) {
            case "string": return String.class;
            case "integer":
            case "int": return Integer.class;
            case "long": return Long.class;
            case "double": return Double.class;
            case "boolean":
            case "bool": return Boolean.class;
            default: throw new IllegalArgumentException("Unsupported capability argument type '" + name + "'.");
        }
    }

    private void verifyTarget(final Action action) {
        if (action == null || action.getTarget() != this) {
            throw new IllegalArgumentException("The action does not target this node.");
        }
    }
}
