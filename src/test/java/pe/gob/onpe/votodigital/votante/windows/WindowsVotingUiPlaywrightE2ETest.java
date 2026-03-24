package pe.gob.onpe.votodigital.votante.windows;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WindowsVotingUiPlaywrightE2ETest {

    private static final int PORT = 8891;
    private static final int SERVICE_PORT = 8892;
    private static final Duration SERVER_TIMEOUT = Duration.ofSeconds(45);

    private static Path projectRoot;
    private static Path classesDir;
    private static Path serverLog;
    private static Process serverProcess;
    private static HttpServer mockService;
    private static String auxsid;
    private static volatile String lastCiphertextsBody;
    private static volatile String lastHandshakeBody;

    @BeforeAll
    static void startServer() throws Exception {
        projectRoot = Path.of("").toAbsolutePath().normalize();
        classesDir = projectRoot.resolve(".build").resolve("votante-windows-e2e").resolve("classes");
        Files.createDirectories(classesDir);
        auxsid = "pw" + System.currentTimeMillis();

        compileWindowsServerSources();
        startMockService();

        Path logDir = projectRoot.resolve("target").resolve("playwright-logs");
        Files.createDirectories(logDir);
        serverLog = logDir.resolve("windows-voter-server.log");

        List<String> command = List.of(
                "java",
                "-Dvotante.windows.root=" + projectRoot,
                "-Dvotante.windows.port=" + PORT,
                "-Dvotante.windows.serviceBaseUrl=http://127.0.0.1:" + SERVICE_PORT,
                "-Dvotante.windows.auxsid=" + auxsid,
                "-cp",
                classesDir.toString(),
                "pe.gob.onpe.votodigital.votante.windows.DidacticWindowsVoterServer"
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(serverLog.toFile());
        serverProcess = processBuilder.start();
        waitForServer();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (serverProcess != null) {
            serverProcess.destroy();
            serverProcess.waitFor();
        }
        if (mockService != null) {
            mockService.stop(0);
        }
    }

    @Test
    void shouldEncryptAndSendVoteFromTheRealUi() throws Exception {
        Path traceDir = projectRoot.resolve("target").resolve("playwright-traces");
        Files.createDirectories(traceDir);
        Path tracePath = traceDir.resolve("windows-voter-ui-e2e.zip");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 1800));
            context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));

            Page page = context.newPage();
            page.navigate("http://127.0.0.1:" + PORT + "/");

            assertEquals("Padron Electoral 2026", page.title());
            assertThat(page.getByTestId("profile-full-name")).containsText("Electo Votario Sufraguez Urnález");
            assertThat(page.getByTestId("profile-dni")).containsText("42777333");
            assertThat(page.getByTestId("service-mix-state")).containsText("Activa");
            assertThat(page.getByTestId("service-endpoint")).containsText("127.0.0.1:" + SERVICE_PORT);
            assertThat(page.getByTestId("service-election-name")).containsText("Elecciones Generales 2026");
            assertThat(page.getByTestId("service-session")).containsText("Servidor E2E 20260320");
            assertThat(page.getByTestId("submit-button")).isEnabled();

            page.getByTestId("info-button").click();
            assertThat(page.getByTestId("info-modal")).isVisible();
            assertThat(page.getByTestId("info-modal-body")).containsText("Estacion de votacion Windows");
            assertThat(page.getByTestId("info-modal-body")).containsText("Cifrador Windows");
            assertThat(page.getByTestId("info-modal-body")).containsText("Software y hardware");
            assertThat(page.getByTestId("info-modal-body")).containsText("Esquema de voto");
            page.getByLabel("Cerrar").click();

            page.selectOption("#presidentialParty", "07");
            page.selectOption("#senatorsNationalParty", "08");
            page.selectOption("#senatorsNationalPv1", "03");
            page.selectOption("#senatorsNationalPv2", "04");
            page.selectOption("#senatorsRegionalParty", "09");
            page.selectOption("#senatorsRegionalPv1", "05");
            page.selectOption("#deputiesParty", "10");
            page.selectOption("#deputiesPv1", "06");
            page.selectOption("#deputiesPv2", "07");
            page.selectOption("#andeanParty", "11");
            page.selectOption("#andeanPv1", "08");
            page.selectOption("#andeanPv2", "09");

            page.getByTestId("preview-button").click();
            assertThat(page.getByTestId("event-monitor")).containsText("Cedula validada y lista para cifrado.");

            page.getByTestId("submit-button").click();

            assertThat(page.getByTestId("receipt-summary")).containsText("Confirmada");
            assertThat(page.getByTestId("receipt-summary")).containsText("Si");
            assertThat(page.getByTestId("receipt-summary")).containsText("Servidor E2E 20260320");
            assertThat(page.getByTestId("receipt-summary")).containsText("Validada en party01");
            assertThat(page.getByTestId("receipt-summary")).containsText("native");
            assertThat(page.getByTestId("receipt-summary")).containsText(auxsid);
            assertThat(page.getByTestId("event-monitor")).containsText("POST http://127.0.0.1:" + SERVICE_PORT + "/api/ciphertexts");
            assertThat(page.getByTestId("event-monitor")).containsText("curl -X POST");
            assertThat(page.getByTestId("event-monitor")).containsText("format_resolved=native");
            assertThat(page.getByTestId("event-monitor")).containsText("Servicio remoto respondio ok=true");

            context.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
            context.close();
            browser.close();
        }

        assertTrue(Files.exists(tracePath), "Debe generarse el trace de Playwright.");
        assertTrue(lastCiphertextsBody != null && lastCiphertextsBody.contains("\"auxsid\":\"" + auxsid + "\""),
                "La API simulada debe recibir el auxsid del envio.");
        assertTrue(lastCiphertextsBody != null && lastCiphertextsBody.contains("\"format\":\"native\""),
                "La API simulada debe recibir format=native.");
        assertTrue(lastCiphertextsBody != null && lastCiphertextsBody.contains("\"session_id\":\"srv-e2e-001\""),
                "La API simulada debe recibir session_id.");
        assertTrue(lastCiphertextsBody != null && lastCiphertextsBody.contains("\"session_name\":\"servidor-e2e-20260320\""),
                "La API simulada debe recibir session_name.");
        assertTrue(lastCiphertextsBody != null && lastCiphertextsBody.contains("\"station_id\":\"mesa_047612\""),
                "La API simulada debe recibir station_id saneado.");
        assertTrue(lastCiphertextsBody != null && lastCiphertextsBody.contains("\"lease_id\":\"mock-e2e-instance:mesa_047612\""),
                "La API simulada debe recibir lease_id.");
        assertTrue(lastCiphertextsBody != null && lastCiphertextsBody.contains("\"ciphertexts_ext\":"),
                "La API simulada debe recibir ciphertexts_ext.");
        assertTrue(lastHandshakeBody != null && lastHandshakeBody.contains("\"station_id\":\"mesa-047612\""),
                "La GUI debe solicitar handshake con la estacion.");
    }

    private static void compileWindowsServerSources() throws Exception {
        List<String> sources = new ArrayList<>();
        try (var stream = Files.walk(projectRoot.resolve("workflow").resolve("votante").resolve("windows").resolve("app").resolve("src"))) {
            stream.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> sources.add(path.toString()));
        }
        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-d");
        command.add(classesDir.toString());
        command.addAll(sources);

        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Fallo la compilacion del servidor Windows para E2E:\n" + output);
        }
    }

    private static void waitForServer() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + SERVER_TIMEOUT.toNanos();
        Exception lastError = null;
        while (System.nanoTime() < deadline) {
            if (serverProcess != null && !serverProcess.isAlive()) {
                String output = Files.exists(serverLog) ? Files.readString(serverLog) : "";
                throw new IllegalStateException("El servidor Windows termino antes del E2E.\n" + output);
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/api/health"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"")) {
                    return;
                }
            } catch (IOException | InterruptedException ex) {
                lastError = ex;
                Thread.sleep(500L);
            }
        }
        throw new IllegalStateException("El servidor Windows no estuvo listo a tiempo.", lastError);
    }

    private static void startMockService() throws Exception {
        Path publicKeyPath = projectRoot.resolve("recursos").resolve("publicKey");
        byte[] publicKeyBytes = Files.readAllBytes(publicKeyPath);
        String publicKeyHex = toHex(publicKeyBytes);

        mockService = HttpServer.create(new InetSocketAddress("127.0.0.1", SERVICE_PORT), 0);
        mockService.createContext("/api/discovery", exchange -> sendJson(exchange, 200,
                "{\"ok\":true,\"server\":{\"instance_id\":\"mock-e2e-instance\",\"hostname\":\"127.0.0.1\","
                        + "\"public_host\":\"127.0.0.1\",\"base_ui_port\":" + SERVICE_PORT + ","
                        + "\"ui_url\":\"http://127.0.0.1:" + SERVICE_PORT + "/\","
                        + "\"api_url\":\"http://127.0.0.1:" + SERVICE_PORT + "\","
                        + "\"discovery_url\":\"http://127.0.0.1:" + SERVICE_PORT + "/api/discovery\","
                        + "\"handshake_url\":\"http://127.0.0.1:" + SERVICE_PORT + "/api/handshake\","
                        + "\"public_key_url\":\"http://127.0.0.1:" + SERVICE_PORT + "/api/public-key\","
                        + "\"ciphertexts_url\":\"http://127.0.0.1:" + SERVICE_PORT + "/api/ciphertexts\","
                        + "\"emission_context_url\":\"http://127.0.0.1:" + SERVICE_PORT + "/api/emission-context\","
                        + "\"has_active_session\":true,\"keygen_ready\":true,\"accepting_votes\":true,"
                        + "\"current_operation\":null,\"handshake_ttl_seconds\":90,\"registered_station_count\":0},"
                        + "\"session\":{\"session_id\":\"srv-e2e-001\",\"session_name\":\"servidor-e2e-20260320\","
                        + "\"session_label\":\"Servidor E2E 20260320\","
                        + "\"election_name\":\"Elecciones Generales 2026\",\"sid\":\"ONPE\"}}"));
        mockService.createContext("/api/handshake", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastHandshakeBody = new String(body, StandardCharsets.UTF_8);
            sendJson(exchange, 200,
                    "{\"ok\":true,\"accepted\":true,\"reason\":\"ok\",\"station_id\":\"mesa_047612\","
                            + "\"requested_auxsid\":\"" + auxsid + "\","
                            + "\"lease_id\":\"mock-e2e-instance:mesa_047612\","
                            + "\"expires_at\":\"2026-03-20T13:18:04-05:00\","
                            + "\"server\":{\"instance_id\":\"mock-e2e-instance\"},"
                            + "\"session\":{\"session_id\":\"srv-e2e-001\","
                            + "\"session_name\":\"servidor-e2e-20260320\","
                            + "\"session_label\":\"Servidor E2E 20260320\","
                            + "\"election_name\":\"Elecciones Generales 2026\",\"sid\":\"ONPE\"},"
                            + "\"session_id\":\"srv-e2e-001\",\"session_name\":\"servidor-e2e-20260320\","
                            + "\"session_label\":\"Servidor E2E 20260320\","
                            + "\"election_name\":\"Elecciones Generales 2026\"}");
        });
        mockService.createContext("/api/state", exchange -> sendJson(exchange, 200,
                "{\"ok\":true,\"session\":\"servidor-e2e-20260320\",\"parties\":[\"party01\",\"party02\",\"party03\"],\"phase\":\"keygen_completed\"}"));
        mockService.createContext("/api/auxsids", exchange -> sendJson(exchange, 200,
                "{\"ok\":true,\"used_auxsids\":[\"default\"],\"reserved_auxsids\":[\"default\",\""
                        + auxsid + "\"],\"pending_ciphertexts\":[\"" + auxsid + "\"],\"next_shuffle\":\""
                        + auxsid + "\",\"next_decrypt\":null,\"next_verify\":null,\"suggested_auxsid\":\"" + auxsid + "\"}"));
        mockService.createContext("/api/emission-context", exchange -> sendJson(exchange, 200,
                "{\"ok\":true,\"session_id\":\"srv-e2e-001\",\"session_name\":\"servidor-e2e-20260320\","
                        + "\"session_label\":\"Servidor E2E 20260320\",\"election_name\":\"Elecciones Generales 2026\","
                        + "\"sid\":\"ONPE\",\"requested_auxsid\":\"" + auxsid + "\",\"resolved_auxsid\":\"" + auxsid + "\","
                        + "\"auxsid\":\"" + auxsid + "\",\"auxsid_changed\":false,\"accumulated\":false,"
                        + "\"accumulated_from_auxsid\":null,\"accepting_votes\":true}"));
        mockService.createContext("/api/public-key", exchange -> sendJson(exchange, 200,
                "{\"ok\":true,\"session_id\":\"srv-e2e-001\",\"session_name\":\"servidor-e2e-20260320\","
                        + "\"session_label\":\"Servidor E2E 20260320\",\"election_name\":\"Elecciones Generales 2026\","
                        + "\"format\":\"native\",\"content\":\"" + publicKeyHex + "\"}"));
        mockService.createContext("/api/ciphertexts", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastCiphertextsBody = new String(body, StandardCharsets.UTF_8);
            sendJson(exchange, 200,
                    "{\"ok\":true,\"validated\":true,\"format_received\":\"native\",\"format_resolved\":\"native\","
                            + "\"input_fields\":[\"ciphertexts_ext\"],\"resolved_auxsid\":\"" + auxsid + "\","
                            + "\"auxsid_changed\":false,\"accumulated\":true,\"handshake_validated\":true,"
                            + "\"station_id\":\"mesa_047612\",\"lease_id\":\"mock-e2e-instance:mesa_047612\","
                            + "\"session_id\":\"srv-e2e-001\",\"session_name\":\"servidor-e2e-20260320\","
                            + "\"session_label\":\"Servidor E2E 20260320\",\"election_name\":\"Elecciones Generales 2026\","
                            + "\"party_validated\":\"party01\",\"width\":1,"
                            + "\"replicated_to\":[\"party01\",\"party02\",\"party03\"],"
                            + "\"written_files\":[\"party01/ciphertexts_ext\",\"party01/ciphertexts\","
                            + "\"party02/ciphertexts_ext\",\"party02/ciphertexts\",\"party03/ciphertexts_ext\","
                            + "\"party03/ciphertexts\"],\"session\":\"servidor-e2e-20260320\"}");
        });
        mockService.start();
    }

    private static void sendJson(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte current : value) {
            builder.append(String.format("%02x", current));
        }
        return builder.toString();
    }
}
