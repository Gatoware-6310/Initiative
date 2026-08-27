package xyz.gatoware.initiative;

import java.io.IOException;
import java.util.Objects;

import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.devices.Device;
import xyz.gatoware.initiative.devices.Node;
import xyz.gatoware.initiative.devices.Registry;
import xyz.gatoware.initiative.web.api.HTTPServer;
import xyz.gatoware.initiative.web.websocket.InitiativeWebSocket;
import xyz.gatoware.utils.serialization.SaveLoadDevices;

public enum Initiative {
	INSTANCE;
	
	public Registry registry;
	public HTTPServer httpServer;
	public InitiativeWebSocket webSocket;
	
	public void initInitiative() {
		registry = new Registry();
		webSocket = new InitiativeWebSocket();
		webSocket.start();
		try {
			if(SaveLoadDevices.getDefaultFile().isFile()) {
				for(Device device : SaveLoadDevices.load()) {
					if(device instanceof Node) {
						webSocket.reconnectNode((Node) device);
					}
				}
			}
			httpServer = new HTTPServer();
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		}
	}

	public Node registerNode(final String name, final String ip) throws IOException {
		if(registry.exists(name)) throw new IllegalArgumentException("You cannot register a device that already exists!");
		final Node node = new Node(name, ip);
		webSocket.connectNode(node);
		registry.registerNode(node);
		return node;
	}

	public String executeAction(final Action action, final Object... arguments) {
		Objects.requireNonNull(action, "An action is required.");
		return action.getTarget().executeAction(action, arguments);
	}

}
