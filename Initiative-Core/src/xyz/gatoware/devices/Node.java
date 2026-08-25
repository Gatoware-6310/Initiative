package xyz.gatoware.devices;

import xyz.gatoware.actions.Action;

public class Node implements Device {
	private String name;
	private int id;
	
	public Node(final String name, final int id) {
		this.name = name;
		this.id = id;
	}

	@Override
	public String getName() {
 		return name;
	}

	@Override
	public int getId() {
		return id;
	}

	@Override
	public void executeAction(Action action) {
		// TODO: implement
	}
}
