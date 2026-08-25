package xyz.gatoware.initiative;

import xyz.gatoware.devices.Registry;

public enum Initiative {
	INSTANCE;
	
	public final Registry registry = new Registry();
}
