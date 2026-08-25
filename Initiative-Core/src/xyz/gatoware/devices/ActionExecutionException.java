package xyz.gatoware.devices;

/** Indicates that a device could not complete an action. */
public final class ActionExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ActionExecutionException(final String scriptPath, final int exitCode, final String output) {
        super("Script '" + scriptPath + "' exited with code " + exitCode + ".\n" + output);
    }

    public ActionExecutionException(final String scriptPath, final Exception cause) {
        super("Could not execute script '" + scriptPath + "'.", cause);
    }
}
