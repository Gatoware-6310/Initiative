package xyz.gatoware.devices;

import xyz.gatoware.actions.Action;
import xyz.gatoware.actions.ActionTypes;

public class External implements Device {
	private String name;
	private int id;
	
	public External(final String name, final int id) {
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
		if(action.getActionType() != ActionTypes.LOCAL_SCRIPT) {
			throw new UnsupportedOperationException("External devices can only use local scripts!");
		}
	}
}
