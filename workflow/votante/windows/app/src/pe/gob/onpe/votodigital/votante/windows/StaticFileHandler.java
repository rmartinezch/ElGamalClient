package pe.gob.onpe.votodigital.votante.windows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class StaticFileHandler implements HttpHandler {

    private final Path publicDir;

    StaticFileHandler(Path publicDir) {
        this.publicDir = publicDir;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            HttpUtil.ensureMethod(exchange, "GET");
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            Path file = publicDir.resolve(path.substring(1)).normalize();
            if (!file.startsWith(publicDir) || !Files.exists(file) || Files.isDirectory(file)) {
                throw new IllegalArgumentException("Recurso no encontrado: " + path);
            }
            exchange.getResponseHeaders().set("Content-Type", guessContentType(file));
            exchange.sendResponseHeaders(200, Files.size(file));
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(file, out);
            }
        } catch (Exception ex) {
            HttpUtil.sendError(exchange, 404, ex);
        }
    }

    private static String guessContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
