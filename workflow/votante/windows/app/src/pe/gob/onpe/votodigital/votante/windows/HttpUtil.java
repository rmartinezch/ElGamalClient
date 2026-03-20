package pe.gob.onpe.votodigital.votante.windows;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpUtil {

    private HttpUtil() {
    }

    static void ensureMethod(HttpExchange exchange, String method) {
        if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("Metodo esperado: " + method
                    + ", recibido: " + exchange.getRequestMethod());
        }
    }

    static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    static Map<String, String> parseForm(String formEncoded) {
        return parseQuery(formEncoded);
    }

    static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] payload)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    static void sendError(HttpExchange exchange, int status, Exception ex) throws IOException {
        if (status >= 500 && !(ex instanceof java.net.ConnectException)) {
            ex.printStackTrace(System.err);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("error", ex.getClass().getSimpleName());
        payload.put("message", ex.getMessage());
        sendJson(exchange, status, payload);
    }
}
