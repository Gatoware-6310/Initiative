package xyz.gatoware.initiative.devices;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Registry {
	private final CopyOnWriteArrayList<Device> deviceList = new CopyOnWriteArrayList<Device>();

	private void registerDevice(final Device device) {
		if(exists(device.getName())) throw new IllegalArgumentException("You cannot register a device that already exists!");
		deviceList.add(Objects.requireNonNull(device, "A device is required."));
	}

	public void registerExternal(final External external) {
		registerDevice(external);
	}

	public void registerNode(final Node node) {
		registerDevice(node);
	}

	public void removeDevice(final Device device) {
		deviceList.remove(device);
	}

	public List<Device> getDevices() {
		return Collections.unmodifiableList(new CopyOnWriteArrayList<Device>(deviceList));
	}
	
	public Device getDeviceFromName(final String name) {
	    return deviceList.stream()
	            .filter(d -> d.getName().equals(name))
	            .findFirst()
	            .orElse(null);
	}
	
	public boolean exists(String name) {
	    return deviceList.stream()
	            .anyMatch(d -> d.getName().equals(name));
	}
}
