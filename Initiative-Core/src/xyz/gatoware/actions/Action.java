package xyz.gatoware.actions;

public class Action {
	private final ActionTypes actionType;

	public Action(final ActionTypes actionType) {
		this.actionType = actionType;
	}

	public ActionTypes getActionType() {
		return actionType;
	}
}
