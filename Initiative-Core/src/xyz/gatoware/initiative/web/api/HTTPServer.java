package xyz.gatoware.initiative.web.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import xyz.gatoware.initiative.Initiative;
import xyz.gatoware.initiative.actions.Action;
import xyz.gatoware.initiative.actions.ActionArgument;
import xyz.gatoware.initiative.devices.Device;
import xyz.gatoware.initiative.devices.External;
import xyz.gatoware.initiative.devices.Node;
import xyz.gatoware.initiative.utils.strings.StringUtils;
import xyz.gatoware.utils.serialization.SaveLoadDevices;

public class HTTPServer implements HttpHandler {
	
	private HttpServer server;
	private ExecutorService executor;
	
	public HTTPServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("0.0.0.0", 1231), 0);
		
		// create endpoints
		server.createContext("/", this::frontend);
		server.createContext("/initiative/health", this::health);
		server.createContext("/initiative/devices", this::listDevices);
		server.createContext("/initiative/devices/status", this::deviceStatus);
		server.createContext("/initiative/devices/actions", this::listActions);
		server.createContext("/initiative/devices/execute", this::executeAction);
		server.createContext("/initiative/devices/python", this::executePython);
		server.createContext("/initiative/devices/registerExternal", this::registerExternal);
		server.createContext("/initiative/devices/registerNode", this::registerNode);
		
		executor = Executors.newFixedThreadPool(8, runnable ->
				new Thread(runnable, "initiative-http-server"));
		server.setExecutor(executor);
		server.start();
		
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down HTTP server...");
            stop();
        }));
	}

	private void frontend(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
		try(InputStream frontend = HTTPServer.class.getResourceAsStream("/xyz/gatoware/initiative/web/initiative_frontend.html")) {
			if(frontend == null) throw new IOException("Could not find the Initiative frontend.");
			sendResponse(new String(frontend.readAllBytes(), StandardCharsets.UTF_8), 200, exchange);
		}
	}
	
	private void health(HttpExchange exchange) throws IOException {
		sendResponse("lookin good!", 200, exchange);
	}
	
	public void deviceStatus(HttpExchange exchange) throws IOException {
		// LOL
		sendResponse(Initiative.INSTANCE.registry.getDeviceFromName(StringUtils.convertInputStreamToString(exchange.getRequestBody())).status(), 200, exchange);
	}
	
	private void listDevices(HttpExchange exchange) throws IOException {
		String response = "";
		for(Device d : Initiative.INSTANCE.registry.getDevices()) {
			response += d.getName() + ":" + (d instanceof Node ? "node" : "external") + "\n";
		}
 		System.out.println(response);
		sendResponse(response, 200, exchange);
	}

	private void listActions(HttpExchange exchange) throws IOException {
		Device device = Initiative.INSTANCE.registry.getDeviceFromName(StringUtils.convertInputStreamToString(exchange.getRequestBody()));
		String response = "";
		for(Action action : device.listActions()) {
			response += action.getName();
			for(ActionArgument argument : action.getArguments()) {
				response += "\t" + argument.getName() + ":" + argument.getType().getSimpleName();
				if(argument.hasRange()) response += ":" + argument.getMinimum() + ":" + argument.getMaximum();
			}
			response += "\n";
		}
		sendResponse(response, 200, exchange);
	}

	private void executeAction(HttpExchange exchange) throws IOException {
		String[] request = StringUtils.convertInputStreamToString(exchange.getRequestBody()).split("\\R");
		Device device = Initiative.INSTANCE.registry.getDeviceFromName(request[0]);
		Action action = device.listActions().stream()
				.filter(a -> a.getName().equals(request[1]))
				.findFirst().orElse(null);
		Object[] arguments = new Object[request.length - 2];
		for(int i = 0; i < arguments.length; i++) {
			arguments[i] = parseValue(request[i + 2], action.getArguments().get(i).getType());
		}
		String response = Initiative.INSTANCE.executeAction(action, arguments);
		sendResponse(response.isEmpty() ? "ok" : response, 200, exchange);
	}

	private void executePython(HttpExchange exchange) throws IOException {
		String request = StringUtils.convertInputStreamToString(exchange.getRequestBody());
		int separator = request.indexOf('\n');
		if(separator < 1 || separator == request.length() - 1) {
			sendResponse("A node name and Python source are required.", 400, exchange);
			return;
		}
		Device device = Initiative.INSTANCE.registry.getDeviceFromName(request.substring(0, separator));
		if(!(device instanceof Node)) {
			sendResponse("That device is not a node.", 400, exchange);
			return;
		}
		try {
			sendResponse(((Node) device).executePython(request.substring(separator + 1)), 200, exchange);
		} catch(IllegalArgumentException exception) {
			sendResponse(exception.getMessage(), 400, exchange);
		} catch(IllegalStateException exception) {
			sendResponse(exception.getMessage(), 504, exchange);
		}
	}

	private void registerExternal(HttpExchange exchange) throws IOException {
		String name = getQueryValue(exchange, "name");
		String filename = getQueryValue(exchange, "filename");
		if(name == null || name.trim().isEmpty() || filename == null || filename.trim().isEmpty()) {
			sendResponse("A device name and Python script are required.", 400, exchange);
			return;
		}
		if(Initiative.INSTANCE.registry.exists(name)) {
			sendResponse("A device with that name already exists.", 409, exchange);
			return;
		}

		String scriptName = Path.of(filename.replace('\\', '/')).getFileName().toString();
		if(!scriptName.endsWith(".py")) {
			sendResponse("Only Python scripts can be registered.", 400, exchange);
			return;
		}
		byte[] script = exchange.getRequestBody().readAllBytes();
		if(script.length == 0) {
			sendResponse("The Python script is empty.", 400, exchange);
			return;
		}

		Path scriptsDirectory = SaveLoadDevices.getDefaultFile().toPath().getParent().resolve("scripts");
		Files.createDirectories(scriptsDirectory);
		Path scriptPath = scriptsDirectory.resolve(scriptName);
		Files.write(scriptPath, script);

		External external = new External(name, scriptPath.toString());
		external.capabilities();
		Initiative.INSTANCE.registry.registerExternal(external);
		SaveLoadDevices.save();
		sendResponse("Registered " + name + ".", 201, exchange);
	}

	private void registerNode(HttpExchange exchange) throws IOException {
		String name = getQueryValue(exchange, "name");
		String ip = getQueryValue(exchange, "ip");
		if(name == null || name.trim().isEmpty() || ip == null || ip.trim().isEmpty()) {
			sendResponse("A node name and IP are required.", 400, exchange);
			return;
		}
		if(Initiative.INSTANCE.registry.exists(name)) {
			sendResponse("A device with that name already exists.", 409, exchange);
			return;
		}

		try {
			Initiative.INSTANCE.registerNode(name, ip);
			SaveLoadDevices.save();
			sendResponse("Registered " + name + ".", 201, exchange);
		} catch(IllegalArgumentException exception) {
			sendResponse(exception.getMessage(), 400, exchange);
		} catch(IllegalStateException exception) {
			sendResponse(exception.getMessage(), 504, exchange);
		} catch(IOException exception) {
			sendResponse(exception.getMessage(), 502, exchange);
		}
	}

	private String getQueryValue(HttpExchange exchange, String key) {
		String query = exchange.getRequestURI().getRawQuery();
		if(query == null) return null;
		for(String field : query.split("&")) {
			int separator = field.indexOf('=');
			if(separator >= 0 && URLDecoder.decode(field.substring(0, separator), StandardCharsets.UTF_8).equals(key)) {
				return URLDecoder.decode(field.substring(separator + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private Object parseValue(String value, Class<?> type) {
		if(type == Integer.class) return Integer.valueOf(value);
		if(type == Long.class) return Long.valueOf(value);
		if(type == Double.class) return Double.valueOf(value);
		if(type == Boolean.class) return Boolean.valueOf(value);
		return value;
	}
	
	public void stop() {
		server.stop(0);
		executor.shutdownNow();
	}
	public void sendResponse(final String response, final int responseCode, HttpExchange exchange) throws IOException {
		final byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.sendResponseHeaders(responseCode, responseBytes.length);
		final OutputStream output = exchange.getResponseBody();
		output.write(responseBytes);
		output.close();
	}
	
	@Override
	public void handle(HttpExchange exchange) throws IOException {
		// final String convertedInput = StringUtils.convertInputStreamToString(exchange.getRequestBody());
		// System.out.println(convertedInput.isEmpty() ? "No message attached to this request!\n" : convertedInput);
	}
}
