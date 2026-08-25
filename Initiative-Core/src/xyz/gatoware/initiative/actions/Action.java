package xyz.gatoware.initiative.actions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import xyz.gatoware.initiative.devices.Device;

/** A self-describing operation. Devices, rather than Actions, execute it. */
public final class Action {
    private final String name;
    private final ActionTypes actionType;
    private final List<ActionArgument> arguments;
    private final Device target;
    private final String content;

    private Action(final String name, final ActionTypes actionType,
            final List<ActionArgument> arguments, final Device target,
            final String content) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("An action name is required.");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("Action content is required.");
        }
        this.name = name;
        this.actionType = Objects.requireNonNull(actionType, "An action type is required.");
        this.arguments = Collections.unmodifiableList(new ArrayList<ActionArgument>(
                Objects.requireNonNull(arguments, "Action arguments are required.")));
        this.target = Objects.requireNonNull(target, "An action target is required.");
        this.content = content;
    }

    public static Action message(final String name, final Device target,
            final String message, final List<ActionArgument> arguments) {
        return new Action(name, ActionTypes.MESSAGE, arguments, target, message);
    }

    /** Creates a local Python-script action. Content is a local script path. */
    public static Action localPython(final String name, final Device target,
            final String scriptPath, final List<ActionArgument> arguments) {
        return new Action(name, ActionTypes.LOCAL_SCRIPT, arguments, target, scriptPath);
    }

    /** Creates a remote Python-script action. Content is Python source code. */
    public static Action remotePython(final String name, final Device target,
            final String pythonSource, final List<ActionArgument> arguments) {
        return new Action(name, ActionTypes.REMOTE_SCRIPT, arguments, target, pythonSource);
    }

    public String getName() {
        return name;
    }

    public ActionTypes getActionType() {
        return actionType;
    }

    public List<ActionArgument> getArguments() {
        return arguments;
    }

    public Device getTarget() {
        return target;
    }

    public String getContent() {
        return content;
    }

    /** Validates values supplied to the Device that executes this action. */
    public void validateArguments(final Object... values) {
        final Object[] supplied = values == null ? new Object[0] : values;
        if (supplied.length != arguments.size()) {
            throw new IllegalArgumentException("Action '" + name + "' expects "
                    + arguments.size() + " argument(s), but received " + supplied.length + ".");
        }
        for (int index = 0; index < arguments.size(); index++) {
            final ActionArgument expected = arguments.get(index);
            final Object value = supplied[index];
            if (value == null || !expected.getType().isInstance(value)) {
                throw new IllegalArgumentException("Argument '" + expected.getName()
                        + "' must be a " + expected.getType().getName() + ".");
            }
        }
    }
}
