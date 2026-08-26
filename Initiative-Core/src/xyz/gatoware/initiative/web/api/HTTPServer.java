package xyz.gatoware.initiative.web.api;

import java.io.IOException;
// import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
import xyz.gatoware.initiative.devices.Node;
import xyz.gatoware.initiative.utils.strings.StringUtils;

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
		sendResponse(Files.readString(Path.of("/home/gatoware/initiative_frontend.html"), StandardCharsets.UTF_8), 200, exchange);
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
		
		final String convertedInput = StringUtils.convertInputStreamToString(exchange.getRequestBody());
		System.out.println(convertedInput.isEmpty() ? "No message attached to this request!\n" : convertedInput);
		
		output.close();
	}
	
	@Override
	public void handle(HttpExchange exchange) throws IOException {
		// final String convertedInput = StringUtils.convertInputStreamToString(exchange.getRequestBody());
		// System.out.println(convertedInput.isEmpty() ? "No message attached to this request!\n" : convertedInput);
	}
}
