package xyz.gatoware.initiative.utils.strings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public class StringUtils {
	public static String convertInputStreamToString(InputStream inputStream) throws IOException {
		StringBuilder textBuilder = new StringBuilder();

		try (Reader reader = new BufferedReader(
		        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

		    int c;
		    while ((c = reader.read()) != -1) {
		        textBuilder.append((char) c);
		    }
		}

		return textBuilder.toString();
	}
}
