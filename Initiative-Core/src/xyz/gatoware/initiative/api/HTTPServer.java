package xyz.gatoware.initiative.api;

import java.io.IOException;
// import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import xyz.gatoware.initiative.utils.strings.StringUtils;

public class HTTPServer implements HttpHandler {
	
	private HttpServer server;
	
	public HTTPServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(1231), 0);
		
		// create endpoints
		server.createContext("/initiative/core/health", this::health);
		server.createContext("/initiative/core/devices", this::listDevices);
		
		server.setExecutor(null);
		server.start();
		
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down HTTP server...");
            server.stop(0);
        }));
	}
	
	private void health(HttpExchange exchange) throws IOException {
		sendResponse("lookin good!", 200, exchange);
	}
	
	private void listDevices(HttpExchange exchange) throws IOException {
		// for(String i);
	}
	
	public void stop() {
		server.stop(0);
	}
	public void sendResponse(final String response, final int responseCode, HttpExchange exchange) throws IOException {
		exchange.sendResponseHeaders(responseCode, response.length());
		final OutputStream output = exchange.getResponseBody();
		output.write(response.getBytes());
		
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
