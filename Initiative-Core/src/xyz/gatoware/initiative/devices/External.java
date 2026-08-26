package xyz.gatoware.initiative.devices;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.Objects;

import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.actions.ActionArgument;
import xyz.gatoware.initiative.actions.ActionTypes;

/**
 * An external device implemented by a local Python script.
 *
 * <p>Every script must implement {@code capabilities} and {@code status}.
 * Integer capability arguments must declare an inclusive range as
 * {@code argumentName:Integer:minimum:maximum}.</p>
 */
public final class External extends Device {
    private final String scriptPath;

    public External(final String name, final String scriptPath) {
        super(name, DeviceType.EXTERNAL);
        if (scriptPath == null || scriptPath.trim().isEmpty()) {
            throw new IllegalArgumentException("A Python script path is required.");
        }
        this.scriptPath = scriptPath;
    }

    public String getScriptPath() {
        return scriptPath;
    }

    /**
     * Returns every command supported by this external device and the typed
     * arguments required to invoke it.
     */
    public List<Action> capabilities() {
        return parseCapabilities(executePythonScript(scriptPath, "capabilities"));
    }

    @Override
    public final List<Action> listActions() {
        return capabilities();
    }

    /** Returns the script output from {@code python3 script.py status}. */
    @Override
    public String status() {
        return executePythonScript(scriptPath, "status").trim();
    }

    @Override
    public final String executeAction(final Action action, final Object... arguments) {
        verifyTarget(action);
        action.validateArguments(arguments);

        if (action.getActionType() != ActionTypes.LOCAL_SCRIPT) {
            throw new UnsupportedOperationException(
                    "External devices only execute LOCAL_SCRIPT actions.");
        }
        return executePythonScript(action.getContent(), prependCommand(action.getName(), arguments));
    }

    /**
     * Runs {@code python3 <scriptPath> <command> <arguments...>} and returns
     * its combined standard output and error output.
     */
    protected String executePythonScript(final String scriptPath, final Object... arguments) {
        final List<String> command = new ArrayList<String>();
        command.add("python3");
        command.add(scriptPath);
        for (final Object argument : arguments) {
            command.add(String.valueOf(argument));
        }

        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            final Process process = processBuilder.start();
            final String output = readOutput(process);
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ActionExecutionException(scriptPath, exitCode, output);
            }
            return output;
        } catch (final IOException exception) {
            throw new ActionExecutionException(scriptPath, exception);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActionExecutionException(scriptPath, exception);
        }
    }

    private void verifyTarget(final Action action) {
        if (action == null) {
            throw new IllegalArgumentException("An action is required.");
        }
        if (action.getTarget() != this) {
            throw new IllegalArgumentException("The action does not target this device.");
        }
    }

    private Object[] prependCommand(final String command, final Object... arguments) {
        final Object[] supplied = arguments == null ? new Object[0] : arguments;
        final Object[] commandArguments = new Object[supplied.length + 1];
        commandArguments[0] = command;
        System.arraycopy(supplied, 0, commandArguments, 1, supplied.length);
        return commandArguments;
    }

    private List<Action> parseCapabilities(final String output) {
        final List<Action> actions = new ArrayList<Action>();
        final String[] lines = output.split("\\R");
        for (final String rawLine : lines) {
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
        return Action.localPython(command, this, scriptPath, arguments);
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
                throw new IllegalArgumentException(
                        "Only Integer capability arguments may declare a range.");
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
        final String normalized = Objects.requireNonNull(name).toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "string":
                return String.class;
            case "integer":
            case "int":
                return Integer.class;
            case "long":
                return Long.class;
            case "double":
                return Double.class;
            case "boolean":
            case "bool":
                return Boolean.class;
            default:
                throw new IllegalArgumentException("Unsupported capability argument type '" + name + "'.");
        }
    }

    private String readOutput(final Process process) throws IOException {
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        return output.toString();
    }
}
