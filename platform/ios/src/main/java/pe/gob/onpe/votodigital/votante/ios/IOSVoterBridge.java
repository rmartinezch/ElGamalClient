package pe.gob.onpe.votodigital.votante.ios;

import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;

public class IOSVoterBridge {

    private static final Logger LOG = Logger.getLogger(IOSVoterBridge.class.getName());
    private static final int VERIFICATUM_WIDTH = 1;
    private static final String SCHEMA_VERSION = "1.0.0-test";
    private static final int TIMEOUT_MS = 15000;

    private final IOSCipherRunner cipherRunner;
    private final IOSMixerLeaseCoordinator mixerCoordinator;
    private WebEngine webEngine;

    public IOSVoterBridge(IOSCipherRunner cipherRunner) {
        this.cipherRunner = cipherRunner;
        this.mixerCoordinator = new IOSMixerLeaseCoordinator("http://192.168.0.120:7040", "mesa-047612");
    }

    public void setWebEngine(WebEngine engine) {
        this.webEngine = engine;
    }

    public void logFromJs(String message) {
        LOG.info("[JS] " + message);
    }

    public String lastCallResult = "";

    public void callApi(String path, String method, String body) {
        System.out.println("[BRIDGE] callApi path=" + path + " method=" + method);
        new Thread(() -> {
            String result;
            try {
                result = switch (path) {
                    case "/api/health" -> jsonObject()
                            .put("status", "UP")
                            .put("serverTime", Instant.now().toString())
                            .build();
                    case "/api/catalog" -> loadCatalogRaw();
                    case "/api/bootstrap" -> handleBootstrap();
                    case "/api/ballot/preview" -> {
                        ensureMethod(method, "POST");
                        yield buildBundle(parseForm(body)).toJson();
                    }
                    case "/api/ballot/submit" -> {
                        ensureMethod(method, "POST");
                        Map<String, String> form = parseForm(body);
                        BallotBundle bundle = buildBundle(form);
                        String preferredAuxsid = firstNonBlank(form.get("auxsid"), "");
                        yield submitBundle(bundle, preferredAuxsid);
                    }
                    default -> errorJson(404, "NotFound", "Ruta no soportada: " + path);
                };
            } catch (IllegalArgumentException ex) {
                System.out.println("[BRIDGE] callApi IllegalArgException: " + ex.getMessage());
                result = errorJson(400, ex.getClass().getSimpleName(),
                        ex.getMessage() != null ? ex.getMessage() : "Solicitud invalida.");
            } catch (Exception ex) {
                System.out.println("[BRIDGE] callApi Exception: " + ex.getClass().getName() + " - " + ex.getMessage());
                LOG.warning("Error en callApi: " + ex.getMessage());
                result = errorJson(500, ex.getClass().getSimpleName(),
                        ex.getMessage() != null ? ex.getMessage() : "Fallo interno.");
            }
            System.out.println("[BRIDGE] callApi result length=" + (result != null ? result.length() : -1));
            pushResultToJs(result);
        }, "bridge-callApi").start();
    }

    private void pushResultToJs(String json) {
        if (webEngine == null || json == null) {
            System.out.println("[BRIDGE] pushResultToJs: webEngine=" + (webEngine != null) + " json=" + (json != null));
            return;
        }
        String b64 = Base64.getEncoder().encodeToString(
                (json != null ? json : "{}").getBytes(StandardCharsets.UTF_8));
        System.out.println("[BRIDGE] pushResultToJs: b64 length=" + b64.length() + " scheduling on FX thread");
        Platform.runLater(() -> {
            try {
                webEngine.executeScript(
                        "window.__handleApiCallback('" + b64 + "')");
                System.out.println("[BRIDGE] pushResultToJs: executeScript completed OK");
            } catch (Exception ex) {
                System.out.println("[BRIDGE] pushResultToJs FAILED: " + ex.getClass().getName()
                        + " - " + ex.getMessage());
            }
        });
    }

    private String handleBootstrap() {
        JsonBuilder payload = configPayload();
        IOSMixerLeaseCoordinator.BootstrapSnapshot snapshot =
                mixerCoordinator.bootstrap(payload.getString("defaultAuxsid"));

        payload.put("serviceBaseUrl", snapshot.serviceBaseUrl());
        payload.put("resolvedAuxsid", snapshot.resolvedAuxsid());
        payload.put("serviceMixActive", snapshot.serviceMixActive());
        payload.put("serviceInactiveReason", snapshot.serviceInactiveReason());
        payload.put("publicKeyOk", snapshot.publicKeyOk());
        payload.put("publicKeyBytes", snapshot.publicKeyBytes());
        payload.put("serviceSessionId", snapshot.serviceSessionId());
        payload.put("serviceSessionName", snapshot.serviceSessionName());
        payload.put("serviceSessionLabel", snapshot.serviceSessionLabel());
        payload.put("serviceElectionName",
                firstNonBlank(snapshot.serviceElectionName(), "Elecciones Generales 2026"));
        payload.put("serviceActiveSession",
                firstNonBlank(snapshot.serviceSessionLabel(),
                        firstNonBlank(snapshot.serviceSessionName(), snapshot.serviceSessionId())));
        payload.put("serviceStateOk", snapshot.serviceMixActive());
        payload.put("serviceOk", snapshot.serviceMixActive());
        payload.put("suggestedAuxsid", snapshot.resolvedAuxsid());
        payload.put("leaseId", snapshot.leaseId());
        payload.put("leaseExpiresAt", snapshot.expiresAt());
        payload.put("stationId", snapshot.stationId());
        payload.put("serviceBusy", snapshot.serviceBusy());
        payload.put("serviceCurrentOperation", snapshot.serviceCurrentOperation());
        payload.put("serviceStateRaw", "");
        payload.put("serviceAuxsidsRaw", "");
        return payload.build();
    }

    private String submitBundle(BallotBundle bundle, String preferredAuxsid) throws Exception {
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        File submissionDir = new File(cipherRunner.getDataDir(), "voter-submissions/" + runId);
        submissionDir.mkdirs();
        List<String> events = new ArrayList<>();
        events.add(event("Inicio de emision. runId=" + runId));

        IOSMixerLeaseCoordinator.LeaseContext lease = mixerCoordinator.ensureLease(preferredAuxsid);
        String resolvedAuxsid = lease.auxsid();
        String serviceBaseUrl = lease.baseUrl();
        events.add(event("Mezcladora seleccionada: " + serviceBaseUrl));
        events.add(event("Handshake activo => station_id=" + lease.stationId()
                + " lease_id=" + lease.leaseId() + " expires_at=" + lease.expiresAt()));
        events.add(event("Auxsid operativo resuelto: " + resolvedAuxsid));

        IOSMixerLeaseCoordinator.PublicKeyInfo publicKey =
                mixerCoordinator.fetchPublicKeyInfo(serviceBaseUrl, "native");
        if (!publicKey.hasKeyMaterial()) {
            throw new IllegalStateException("La mezcladora no reporta una sesion activa para recibir votos.");
        }
        String serviceSessionId = firstNonBlank(publicKey.sessionId(), lease.sessionId());
        String serviceSessionName = firstNonBlank(publicKey.sessionName(), lease.sessionName());
        String serviceSessionLabel = firstNonBlank(publicKey.sessionLabel(), lease.sessionLabel());
        events.add(event("Llave publica nativa descargada. bytes=" + publicKey.contentBytes().length));

        File publicKeyFile = cipherRunner.currentPublicKeyFile();
        publicKeyFile.getParentFile().mkdirs();
        Files.write(publicKeyFile.toPath(), publicKey.contentBytes());

        File votesFile = cipherRunner.currentVotesFile();
        votesFile.getParentFile().mkdirs();
        Files.writeString(votesFile.toPath(), bundle.bundleText(), StandardCharsets.UTF_8);
        events.add(event("Cedula canonica validada y serializada en plain_votes.txt"));
        events.add(event("Invocando cifrador desacoplado del ejecutable."));

        IOSCipherRunner.CipherResult cipherResult = cipherRunner.encryptSandboxInputs(CifradorRngMode.SOFTWARE);
        events.add(event("Proceso de cifrado finalizado. exitCode=" + (cipherResult.success() ? 0 : 1)));
        if (!cipherResult.success()) {
            throw new IllegalStateException("No se genero ciphertexts_ext. runId=" + runId);
        }

        String ciphertextsText = Files.readString(cipherResult.outputFile().toPath(), StandardCharsets.UTF_8);
        int ciphertextCount = countNonBlankLines(ciphertextsText);
        events.add(event("Voto cifrado generado. registros=" + ciphertextCount));

        String postPayload = jsonObject()
                .put("station_id", lease.stationId())
                .put("lease_id", lease.leaseId())
                .put("auxsid", resolvedAuxsid)
                .put("format", "native")
                .put("ciphertexts_ext", ciphertextsText)
                .put("width", VERIFICATUM_WIDTH)
                .putIf(serviceSessionId, "session_id", serviceSessionId)
                .putIf(serviceSessionName, "session_name", serviceSessionName)
                .build();

        events.add(event("POST " + serviceBaseUrl + "/api/ciphertexts"));
        events.add(event("Remitiendo ciphertexts_ext al servicio remoto con format=native."));

        String receiptRaw = httpPostJson(serviceBaseUrl + "/api/ciphertexts", postPayload);
        boolean receiptAccepted = receiptRaw.contains("\"ok\":true") || receiptRaw.contains("\"ok\": true");
        events.add(event("Servicio remoto respondio ok=" + receiptAccepted + " auxsid=" + resolvedAuxsid));

        copyFile(publicKeyFile, new File(submissionDir, "publicKey"));
        copyFile(votesFile, new File(submissionDir, "plain_votes.txt"));
        copyFile(cipherResult.outputFile(), new File(submissionDir, "ciphertexts_ext"));
        Files.writeString(new File(submissionDir, "receipt.json").toPath(), receiptRaw, StandardCharsets.UTF_8);

        return jsonObject()
                .put("runId", runId)
                .put("auxsid", resolvedAuxsid)
                .put("serviceResolvedAuxsid", resolvedAuxsid)
                .put("serviceSessionId", serviceSessionId)
                .put("serviceSessionName", serviceSessionName)
                .put("serviceSessionLabel", serviceSessionLabel)
                .put("serviceAccumulated", false)
                .put("serviceAccumulatedFromAuxsid", "")
                .put("receiptAccepted", receiptAccepted)
                .put("submissionDir", submissionDir.getAbsolutePath())
                .put("receiptRaw", receiptRaw)
                .putJsonArray("events", events)
                .put("monitorText", String.join("\n", events))
                .put("width", VERIFICATUM_WIDTH)
                .put("publicKeyFormat", "native")
                .put("ciphertextsFormat", "native")
                .put("voteSchemaVersion", SCHEMA_VERSION)
                .build();
    }

    private JsonBuilder configPayload() {
        return jsonObject()
                .put("serviceBaseUrl", mixerCoordinator.activeBaseUrl())
                .put("defaultAuxsid", "")
                .put("electionName", "Elecciones Generales 2026")
                .putJson("voterProfile", voterProfile())
                .put("stationId", "mesa-047612");
    }

    private String voterProfile() {
        return jsonObject()
                .put("fullName", "Electo Votario Sufraguez Urnález")
                .put("dni", "42777333")
                .put("electoralDistrict", "LIMA METROPOLITANA")
                .put("districtCode", "02")
                .put("local", "I.E. 7086 LOS JARDINES")
                .put("mesa", "047612")
                .put("order", "218")
                .put("ubigeo", "150101")
                .put("condition", "HABIL PARA SUFRAGAR")
                .put("emissionWindow", "08:00 - 16:00")
                .build();
    }

    private String loadCatalogRaw() {
        try (InputStream is = getClass().getResourceAsStream("/votante/catalogo-opciones.json")) {
            if (is == null) {
                return "{}";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "{}";
        }
    }

    private Map<String, String> parseForm(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) continue;
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private BallotBundle buildBundle(Map<String, String> form) {
        String districtCode = normalizeRequiredCode("districtCode", form.get("districtCode"));
        String presidentialParty = normalizeRequiredCode("presidentialParty", form.get("presidentialParty"));
        String senatorsNationalParty = normalizeRequiredCode("senatorsNationalParty", form.get("senatorsNationalParty"));
        String senatorsNationalPv1 = normalizeOptionalCode(form.get("senatorsNationalPv1"));
        String senatorsNationalPv2 = normalizeOptionalCode(form.get("senatorsNationalPv2"));
        String senatorsRegionalParty = normalizeRequiredCode("senatorsRegionalParty", form.get("senatorsRegionalParty"));
        String senatorsRegionalPv1 = normalizeOptionalCode(form.get("senatorsRegionalPv1"));
        String deputiesParty = normalizeRequiredCode("deputiesParty", form.get("deputiesParty"));
        String deputiesPv1 = normalizeOptionalCode(form.get("deputiesPv1"));
        String deputiesPv2 = normalizeOptionalCode(form.get("deputiesPv2"));
        String andeanParty = normalizeRequiredCode("andeanParty", form.get("andeanParty"));
        String andeanPv1 = normalizeOptionalCode(form.get("andeanPv1"));
        String andeanPv2 = normalizeOptionalCode(form.get("andeanPv2"));

        if (!"00".equals(senatorsNationalPv1) && senatorsNationalPv1.equals(senatorsNationalPv2)) {
            senatorsNationalPv2 = "00";
        }
        if (!"00".equals(deputiesPv1) && deputiesPv1.equals(deputiesPv2)) {
            deputiesPv2 = "00";
        }
        if (!"00".equals(andeanPv1) && andeanPv1.equals(andeanPv2)) {
            andeanPv2 = "00";
        }

        List<String> lines = List.of(
                "0100" + presidentialParty + "0000",
                "0200" + senatorsNationalParty + senatorsNationalPv1 + senatorsNationalPv2,
                "03" + districtCode + senatorsRegionalParty + senatorsRegionalPv1 + "00",
                "04" + districtCode + deputiesParty + deputiesPv1 + deputiesPv2,
                "0500" + andeanParty + andeanPv1 + andeanPv2
        );
        return new BallotBundle(districtCode, lines);
    }

    private String normalizeRequiredCode(String field, String value) {
        String normalized = normalizeOptionalCode(value);
        if ("00".equals(normalized)) {
            throw new IllegalArgumentException("Campo obligatorio faltante: " + field);
        }
        return normalized;
    }

    private String normalizeOptionalCode(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return "00";
        }
        if (!trimmed.matches("\\d{2}")) {
            throw new IllegalArgumentException("Codigo invalido, se esperaban 2 digitos: " + trimmed);
        }
        return trimmed;
    }

    private String httpPostJson(String urlStr, String payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = (status >= 200 && status < 300)
                ? connection.getInputStream() : connection.getErrorStream();
        String body = stream != null ? new String(stream.readAllBytes(), StandardCharsets.UTF_8) : "";
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " " + body.substring(0, Math.min(240, body.length())));
        }
        return body;
    }

    private int countNonBlankLines(String value) {
        return (int) value.replace("\r", "").lines().filter(l -> !l.isBlank()).count();
    }

    private void copyFile(File source, File target) {
        if (source.isFile()) {
            try {
                Files.copy(source.toPath(), target.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private String event(String message) {
        return Instant.now() + " | " + message;
    }

    private void ensureMethod(String actual, String expected) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("Metodo esperado: " + expected + ", recibido: " + actual);
        }
    }

    private String errorJson(int status, String error, String message) {
        return jsonObject()
                .put("status", status)
                .put("error", error)
                .put("message", message)
                .build();
    }

    static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred.trim();
        if (fallback != null && !fallback.isBlank()) return fallback.trim();
        return "";
    }

    record BallotBundle(String districtCode, List<String> lines) {
        String bundleText() {
            return String.join("\n", lines) + "\n";
        }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"districtCode\":\"").append(districtCode).append("\",\"lineCount\":")
                    .append(lines.size()).append(",\"lines\":[");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(lines.get(i)).append("\"");
            }
            sb.append("],\"bundleText\":\"").append(escapeJsonString(bundleText())).append("\"}");
            return sb.toString();
        }
    }

    static String escapeJsonString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static JsonBuilder jsonObject() {
        return new JsonBuilder();
    }

    static final class JsonBuilder {
        private final List<String> entries = new ArrayList<>();

        JsonBuilder put(String key, String value) {
            entries.add("\"" + key + "\":\"" + escapeJsonString(value == null ? "" : value) + "\"");
            return this;
        }

        JsonBuilder put(String key, int value) {
            entries.add("\"" + key + "\":" + value);
            return this;
        }

        JsonBuilder put(String key, boolean value) {
            entries.add("\"" + key + "\":" + value);
            return this;
        }

        JsonBuilder putJson(String key, String rawJson) {
            entries.add("\"" + key + "\":" + rawJson);
            return this;
        }

        JsonBuilder putIf(String condition, String key, String value) {
            if (condition != null && !condition.isBlank()) {
                put(key, value);
            }
            return this;
        }

        JsonBuilder putJsonArray(String key, List<String> items) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJsonString(items.get(i))).append("\"");
            }
            sb.append("]");
            entries.add("\"" + key + "\":" + sb);
            return this;
        }

        String getString(String key) {
            String prefix = "\"" + key + "\":\"";
            for (String entry : entries) {
                if (entry.startsWith(prefix)) {
                    return entry.substring(prefix.length(), entry.length() - 1);
                }
            }
            return "";
        }

        String build() {
            return "{" + String.join(",", entries) + "}";
        }
    }
}
