package xyz.gatoware.initiative;

import java.io.IOException;
import java.util.Objects;

import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.devices.Registry;
import xyz.gatoware.initiative.web.api.HTTPServer;

public enum Initiative {
	INSTANCE;
	
	public Registry registry;
	public HTTPServer httpServer;
	
	public void initInitiative() {
		registry = new Registry();
		try {
			httpServer = new HTTPServer();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public String executeAction(final Action action, final Object... arguments) {
		Objects.requireNonNull(action, "An action is required.");
		return action.getTarget().executeAction(action, arguments);
	}

}
