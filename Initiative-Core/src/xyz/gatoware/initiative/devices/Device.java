package xyz.gatoware.initiative.devices;

import java.util.List;
import java.util.Objects;

import xyz.gatoware.initiative.actions.Action;

/** Common representation of something Initiative can observe or control. */
public abstract class Device {
    private final String name;
    private final DeviceType type;

    protected Device(final String name, final DeviceType type) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("A device name is required.");
        }
        this.name = name;
        this.type = Objects.requireNonNull(type, "A device type is required.");
    }

    public final String getName() {
        return name;
    }

    public final DeviceType getType() {
        return type;
    }

    /** Returns a device-specific snapshot of its current state. */
    public abstract Object status();

    /** Returns the actions currently available on this device. */
    public abstract List<Action> listActions();

    /**
     * Performs an action targeted at this device. Script actions return the
     * program output; message actions return an empty string unless their
     * device protocol supplies a response.
     */
    public abstract String executeAction(Action action, Object... arguments);
}
