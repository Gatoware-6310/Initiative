package xyz.gatoware.devices;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Registry {
	private final CopyOnWriteArrayList<Device> deviceList = new CopyOnWriteArrayList<Device>();

	public void register(final Device device) {
		deviceList.add(Objects.requireNonNull(device, "A device is required."));
	}

	public void remove(final Device device) {
		deviceList.remove(device);
	}

	public List<Device> getList() {
		return Collections.unmodifiableList(new CopyOnWriteArrayList<Device>(deviceList));
	}
}
