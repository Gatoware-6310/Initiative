package xyz.gatoware.initiative;

import java.io.IOException;
import java.util.Objects;

import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.devices.Registry;
import xyz.gatoware.initiative.web.api.HTTPServer;
import xyz.gatoware.utils.serialization.SaveLoadDevices;

public enum Initiative {
	INSTANCE;
	
	public Registry registry;
	public HTTPServer httpServer;
	
	public void initInitiative() {
		registry = new Registry();
		try {
			SaveLoadDevices.load();
			httpServer = new HTTPServer();
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			
			System.out.println("HTTP Server failed, or the Initiative directory/devices file was not found!");
		}
	}

	public String executeAction(final Action action, final Object... arguments) {
		Objects.requireNonNull(action, "An action is required.");
		return action.getTarget().executeAction(action, arguments);
	}

}
