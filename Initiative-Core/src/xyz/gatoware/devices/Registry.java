package xyz.gatoware.devices;

import java.util.concurrent.CopyOnWriteArrayList;

public class Registry {
	private CopyOnWriteArrayList<Device> deviceList = new CopyOnWriteArrayList<Device>();
	
	public void register(Device device) {
		deviceList.add(device);
	}
	
	public void remove(Device device) {
		deviceList.remove(device);
	}
	
	public CopyOnWriteArrayList<Device> getList() {
		return deviceList;
	}
}
