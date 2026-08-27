package xyz.gatoware.initiative.web.websocket;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.WebSocket;
import org.java_websocket.server.WebSocketServer;

import xyz.gatoware.initiative.devices.Node;
import xyz.gatoware.initiative.Initiative;

public class InitiativeWebSocket extends WebSocketServer {
	private final Map<String, Node> pendingNodes = new ConcurrentHashMap<String, Node>();
	private final Map<WebSocket, Node> nodes = new ConcurrentHashMap<WebSocket, Node>();
	private final Map<String, Boolean> reconnectingNodes = new ConcurrentHashMap<String, Boolean>();

	public InitiativeWebSocket() {
		super(new InetSocketAddress(1232));
	}

	public void connectNode(Node node) throws IOException {
		node.prepareConnection();
		pendingNodes.put(node.getName(), node);
		try {
			final String coreIp = getCoreIp(node.getIp());
			final String path = "/initiative/nodes/" + URLEncoder.encode(node.getName(), StandardCharsets.UTF_8);
			poke(node.getIp(), "ws://" + coreIp + ":1232" + path);
			if (!node.awaitConnection()) {
				throw new IOException("Node '" + node.getName() + "' did not connect.");
			}
			node.listActions();
		} catch (final RuntimeException | IOException exception) {
			pendingNodes.remove(node.getName(), node);
			throw exception;
		}
	}

	public void reconnectNode(final Node node) {
		if(reconnectingNodes.putIfAbsent(node.getName(), Boolean.TRUE) != null) return;
		final Thread reconnect = new Thread(() -> {
			try {
				while(!node.isConnected()) {
					try {
						connectNode(node);
					} catch(IOException | IllegalStateException exception) {
						try {
							Thread.sleep(1000);
						} catch(InterruptedException interrupted) {
							Thread.currentThread().interrupt();
							return;
						}
					}
				}
			} finally {
				reconnectingNodes.remove(node.getName());
			}
		}, "initiative-node-reconnect-" + node.getName());
		reconnect.setDaemon(true);
		reconnect.start();
	}

	private void poke(String ip, String websocketUrl) throws IOException {
		final HttpURLConnection request = (HttpURLConnection) new URL("http://" + ip + ":1233/initiative/connect").openConnection();
		request.setConnectTimeout(5000);
		request.setReadTimeout(5000);
		request.setRequestMethod("POST");
		request.setDoOutput(true);
		final byte[] body = websocketUrl.getBytes(StandardCharsets.UTF_8);
		request.setFixedLengthStreamingMode(body.length);
		request.getOutputStream().write(body);
		final int responseCode = request.getResponseCode();
		if (responseCode != 202) {
			throw new IOException("Node HTTP server returned " + responseCode + ".");
		}
	}

	private String getCoreIp(String nodeIp) throws IOException {
		try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("0.0.0.0"))) {
			socket.connect(InetAddress.getByName(nodeIp), 1233);
			return socket.getLocalAddress().getHostAddress();
		}
	}
	
	@Override
	public void onClose(WebSocket connection, int code, String reason, boolean remote) {
		Node node = nodes.remove(connection);
		if(node != null) {
			node.disconnect(connection);
			if(Initiative.INSTANCE.registry.getDeviceFromName(node.getName()) == node) reconnectNode(node);
		}
		System.out.println("Connection closed! Code: " + code + " Reason: " + (reason.isEmpty() ? "N/A" : reason) + " Remote: " + remote);
	}
		

	@Override
	public void onError(WebSocket connection, Exception exception) {
		System.out.println("Exception!");
		exception.printStackTrace();
	}

	@Override
	public void onMessage(WebSocket connection, String message) {
		Node node = nodes.get(connection);
		if(node != null) node.reply(message);
		System.out.println("Heard: " + message);
	}

	@Override
	public void onOpen(WebSocket connection, ClientHandshake handshake) {
		String resource = handshake.getResourceDescriptor();
		String prefix = "/initiative/nodes/";
		if(!resource.startsWith(prefix)) {
			connection.close(1008, "A node name is required.");
			return;
		}
		String name = URLDecoder.decode(resource.substring(prefix.length()), StandardCharsets.UTF_8);
		Node node = pendingNodes.remove(name);
		if(node == null) {
			connection.close(1008, "Node registration is not pending.");
			return;
		}
		nodes.put(connection, node);
		node.connect(connection);
		System.out.println("Connected: " + connection.getRemoteSocketAddress());
	}

	@Override
	public void onStart() {
		System.out.println("WS Server started!");
	}

	public void sendBroadcast(String message) {
	    broadcast(message);
	}
	
}
