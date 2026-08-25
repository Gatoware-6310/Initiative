package xyz.gatoware.actions;

import java.util.Objects;

/** Describes one argument accepted by an action. */
public final class ActionArgument {
    private final String name;
    private final Class<?> type;
    private final Integer minimum;
    private final Integer maximum;

    public ActionArgument(final String name, final Class<?> type) {
        this(name, type, null, null);
    }

    /** Creates an integer argument with an inclusive minimum and maximum. */
    public ActionArgument(final String name, final Integer minimum, final Integer maximum) {
        this(name, Integer.class, minimum, maximum);
    }

    private ActionArgument(final String name, final Class<?> type,
            final Integer minimum, final Integer maximum) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("An action argument name is required.");
        }
        this.name = name;
        this.type = Objects.requireNonNull(type, "An action argument type is required.");
        if (minimum != null || maximum != null) {
            if (type != Integer.class || minimum == null || maximum == null) {
                throw new IllegalArgumentException(
                        "Only Integer arguments can have a minimum and maximum.");
            }
            if (minimum.intValue() > maximum.intValue()) {
                throw new IllegalArgumentException("An argument minimum cannot exceed its maximum.");
            }
        }
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public String getName() {
        return name;
    }

    public Class<?> getType() {
        return type;
    }

    public boolean hasRange() {
        return minimum != null;
    }

    public Integer getMinimum() {
        return minimum;
    }

    public Integer getMaximum() {
        return maximum;
    }
}
