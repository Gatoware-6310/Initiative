package xyz.gatoware.initiative;

import java.util.List;
import java.util.Objects;

import xyz.gatoware.actions.Action;
import xyz.gatoware.devices.Device;
import xyz.gatoware.devices.Registry;

public enum Initiative {
	INSTANCE;
	
	public final Registry registry = new Registry();

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
