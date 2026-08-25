package xyz.gatoware.devices;

import xyz.gatoware.actions.Action;

public interface Device {
	String name = "";
	int id = 0;

	public String getName();

	public int getId();
	
	public void executeAction(Action action);
}
