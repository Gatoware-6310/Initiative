package xyz.gatoware.initiative;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.api.HTTPServer;
import xyz.gatoware.initiative.devices.Device;
import xyz.gatoware.initiative.devices.Registry;

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

	public void registerDevice(final Device device) {
		registry.register(device);
	}

	public void removeDevice(final Device device) {
		registry.remove(device);
	}

	public List<Device> getDevices() {
		return registry.getList();
	}

	public String executeAction(final Action action, final Object... arguments) {
		Objects.requireNonNull(action, "An action is required.");
		return action.getTarget().executeAction(action, arguments);
	}

}
