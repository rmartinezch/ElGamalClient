package pe.gob.onpe.votodigital.votante.windows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DidacticWindowsVoterServer {

    private static final String SCHEMA_VERSION = "1.0.0-test";
    private static final String DEFAULT_INTERFACE_DISPLAY_NAME = "Estacion de votacion Windows";
    private static final String INTERFACE_VERSION = "0.1.0";
    private static final String DEFAULT_CIPHER_DISPLAY_NAME = "Cifrador Windows";
    private static final String DEFAULT_CIPHER_RNG_SUPPORT = "Software y hardware (TrueRNG USB/COM)";
    private static final int VERIFICATUM_WIDTH = 1;
    private static final String DEFAULT_SERVICE_BASE_URL = "";
    private static final DateTimeFormatter BUILD_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
    private static final Pattern JSON_STRING_FIELD =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(null|\"([^\"]*)\")");
    private static final Pattern JSON_BOOLEAN_FIELD =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(true|false)");

    private DidacticWindowsVoterServer() {
    }

    public static void main(String[] args) throws Exception {
        String stationId = readStringProperty("votante.station.id", "windows");
        AppConfig config = loadConfig(
            stationId,
            stationProperty("bindHost", "votante.windows.bindHost", "0.0.0.0"),
            stationProperty("publicHost", "votante.windows.publicHost", ""),
            Integer.parseInt(stationProperty("port", "votante.windows.port", "8788")),
            stationProperty("serviceBaseUrl", "votante.windows.serviceBaseUrl", DEFAULT_SERVICE_BASE_URL),
            stationProperty("auxsid", "votante.windows.auxsid", "")
        );

        HttpServer server = HttpServer.create(new InetSocketAddress(config.bindHost, config.port), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        registerContexts(server, config);
        server.start();

        System.out.println("WindowsVoterServer escuchando en http://"
                + config.bindHost + ":" + config.port);
        System.out.println("Acceso local: " + config.localBaseUrl());
        if (!config.publicBaseUrl().equals(config.localBaseUrl())) {
            System.out.println("Acceso red local: " + config.publicBaseUrl());
        }
        System.out.println(config.cipherDisplayName + " consumido como libreria usando " + config.javaExePath());
        if (config.serviceBaseUrl != null && !config.serviceBaseUrl.isBlank()) {
            System.out.println("Semilla de descubrimiento configurada: " + config.serviceBaseUrl);
        } else {
            System.out.println("Descubrimiento de mezcladora: red local");
        }
    }

    static AppConfig loadConfig(String stationId, String bindHost, String publicHost, int port, String serviceBaseUrl,
                                String defaultAuxsid)
            throws java.io.IOException {
        Path rootDir = resolveRootDir();
        String normalizedStationId = firstNonBlank(stationId, "windows");
        Path appDir = rootDir.resolve("workflow").resolve("votante").resolve(normalizedStationId).resolve("app");
        Path defaultPublicDir = appDir.resolve("public");
        String configuredPublicDir = readStringProperty("votante.station.publicDir", "");
        Path publicDir = configuredPublicDir.isBlank()
            ? defaultPublicDir
            : Path.of(configuredPublicDir).toAbsolutePath().normalize();
        Path runtimeDir = rootDir.resolve("workflow").resolve("votante").resolve(normalizedStationId).resolve("runtime");
        Path submissionsDir = runtimeDir.resolve("submissions");
        Path catalogPath = rootDir.resolve("workflow").resolve("votante")
                .resolve("shared").resolve("catalogo-opciones.json");
        Path schemaPath = rootDir.resolve("workflow").resolve("votante")
                .resolve("shared").resolve("vote-schema.md");
        String configuredCifradorDir = readStringProperty("votante.station.cifradorDir", "");
        Path cifradorDir = configuredCifradorDir.isBlank()
            ? rootDir.resolve("dist").resolve("windows").resolve("library").resolve("Cifrador")
            : Path.of(configuredCifradorDir).toAbsolutePath().normalize();
        Files.createDirectories(submissionsDir);
        return new AppConfig(
                rootDir,
                publicDir,
                runtimeDir,
                submissionsDir,
                catalogPath,
                schemaPath,
                cifradorDir,
                bindHost,
                resolvePublicHost(bindHost, publicHost),
                port,
                trimTrailingSlash(serviceBaseUrl),
                defaultAuxsid,
                normalizedStationId,
                readStringProperty("votante.station.interfaceDisplayName", DEFAULT_INTERFACE_DISPLAY_NAME),
                readStringProperty("votante.station.cipherDisplayName", DEFAULT_CIPHER_DISPLAY_NAME),
                readStringProperty("votante.station.cipherRngSupport", DEFAULT_CIPHER_RNG_SUPPORT),
                readStringProperty("votante.station.cifradorJarName", "ElGamalCipher-1.1.0.jar"),
                readStringProperty("votante.station.cifradorNativeSubdir", "windows-x64"),
                readStringProperty("votante.station.javaBinName", defaultJavaBinaryName()),
                VoterProfile.demo(),
                new MixerDiscoveryCoordinator(
                        trimTrailingSlash(serviceBaseUrl),
                        "mesa-" + VoterProfile.demo().mesa()
                )
        );
    }

    static void registerContexts(HttpServer server, AppConfig config) {
        HttpClient httpClient = HttpClient.newBuilder().build();

        server.createContext("/favicon.ico", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        server.createContext("/api/health", exchange -> json(exchange, () -> Map.of(
                "status", "UP",
                "serverTime", Instant.now().toString()
        )));

        server.createContext("/api/config", exchange -> json(exchange, () -> {
            HttpUtil.ensureMethod(exchange, "GET");
            return configPayload(config);
        }));

        server.createContext("/api/bootstrap", exchange -> json(exchange, () -> {
            Map<String, Object> payload = new LinkedHashMap<>(configPayload(config));
            HttpUtil.ensureMethod(exchange, "GET");
            MixerDiscoveryCoordinator.BootstrapSnapshot snapshot =
                    config.mixerCoordinator.bootstrap(httpClient, config.defaultAuxsid);
            payload.put("serviceBaseUrl", snapshot.serviceBaseUrl());
            payload.put("resolvedAuxsid", snapshot.resolvedAuxsid());
            payload.put("serviceMixActive", snapshot.serviceMixActive());
            payload.put("serviceInactiveReason", snapshot.serviceInactiveReason());
            payload.put("publicKeyOk", snapshot.publicKeyOk());
            payload.put("publicKeyBytes", snapshot.publicKeyBytes());
            payload.put("serviceSessionId", snapshot.serviceSessionId());
            payload.put("serviceSessionName", snapshot.serviceSessionName());
            payload.put("serviceSessionLabel", snapshot.serviceSessionLabel());
            payload.put("serviceElectionName", firstNonBlank(snapshot.serviceElectionName(), "Elecciones Generales 2026"));
            payload.put("serviceActiveSession", firstNonBlank(snapshot.serviceSessionLabel(),
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
            return payload;
        }));

        server.createContext("/api/service/auxsids", exchange -> proxyServiceJson(exchange, httpClient,
                activeServiceBaseUrl(config) + "/api/auxsids"));
        server.createContext("/api/service/emission-context", exchange -> proxyServiceJson(exchange, httpClient,
                activeServiceBaseUrl(config) + "/api/emission-context"));
        server.createContext("/api/service/state", exchange -> proxyServiceJson(exchange, httpClient,
                activeServiceBaseUrl(config) + "/api/state"));

        server.createContext("/api/service/public-key", exchange -> {
            try {
                HttpUtil.ensureMethod(exchange, "GET");
                byte[] payload = fetchPublicKey(httpClient, activeServiceBaseUrl(config), "native");
                HttpUtil.sendBytes(exchange, 200, "application/octet-stream", payload);
            } catch (Exception ex) {
                HttpUtil.sendError(exchange, 500, ex);
            }
        });

        server.createContext("/api/catalog", exchange -> {
            try {
                HttpUtil.ensureMethod(exchange, "GET");
                HttpUtil.sendBytes(exchange, 200, "application/json; charset=utf-8",
                        Files.readAllBytes(config.catalogPath));
            } catch (Exception ex) {
                HttpUtil.sendError(exchange, 500, ex);
            }
        });

        server.createContext("/api/ballot/preview", exchange -> json(exchange, () -> {
            HttpUtil.ensureMethod(exchange, "POST");
            Map<String, String> form = HttpUtil.parseForm(HttpUtil.readRequestBody(exchange));
            return buildBundle(form).toMap();
        }));

        server.createContext("/api/ballot/submit", exchange -> json(exchange, () -> {
            HttpUtil.ensureMethod(exchange, "POST");
            Map<String, String> form = HttpUtil.parseForm(HttpUtil.readRequestBody(exchange));
            BallotBundle bundle = buildBundle(form);
            String preferredAuxsid = firstNonBlank(form.get("auxsid"), config.defaultAuxsid);
            return submitBundle(config, httpClient, bundle, preferredAuxsid).toMap();
        }));

        server.createContext("/", new StaticFileHandler(config.publicDir));
    }

    private static Map<String, Object> configPayload(AppConfig config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bindHost", config.bindHost);
        payload.put("publicHost", config.publicHost);
        payload.put("port", config.port);
        payload.put("localBaseUrl", config.localBaseUrl());
        payload.put("publicBaseUrl", config.publicBaseUrl());
        payload.put("serviceBaseUrl", activeServiceBaseUrl(config));
        payload.put("defaultAuxsid", config.defaultAuxsid);
        payload.put("stationId", "mesa-" + config.voterProfile.mesa());
        payload.put("catalogPath", config.catalogPath.toString());
        payload.put("schemaPath", config.schemaPath.toString());
        payload.put("cifradorDir", config.cifradorDir.toString());
        payload.put("javaExe", config.javaExePath().toString());
        payload.put("jarPath", config.jarPath().toString());
        payload.put("usesExe", false);
        payload.put("electionName", "Elecciones Generales 2026");
        payload.put("interfaceDisplayName", config.interfaceDisplayName);
        payload.put("interfaceVersion", INTERFACE_VERSION);
        payload.put("interfaceBuildTimestamp", resolveInterfaceBuildTimestamp(config));
        payload.put("cipherDisplayName", config.cipherDisplayName);
        payload.put("cipherVersion", resolveCipherVersion(config.jarPath()));
        payload.put("cipherBuildTimestamp", formatTimestamp(lastModified(config.jarPath())));
        payload.put("cipherRngSupport", config.cipherRngSupport);
        payload.put("voteSchemaVersion", SCHEMA_VERSION);
        payload.put("voterProfile", config.voterProfile.toMap());
        return payload;
    }

    private static String resolveInterfaceBuildTimestamp(AppConfig config) {
        Path appDir = config.publicDir.getParent();
        List<Path> candidates = List.of(
                appDir.resolve("classes"),
                appDir.resolve("src"),
                config.publicDir
        );
        FileTime latest = null;
        for (Path candidate : candidates) {
            latest = maxTime(latest, lastModifiedRecursive(candidate));
        }
        return formatTimestamp(latest);
    }

    private static String resolveCipherVersion(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        Matcher matcher = Pattern.compile("ElGamalCipher-([0-9][^.]*(?:\\.[0-9A-Za-z_-]+)*)\\.jar")
                .matcher(fileName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return "no-informada";
    }

    private static String formatTimestamp(FileTime value) {
        if (value == null) {
            return "no-informada";
        }
        return BUILD_TIMESTAMP_FORMATTER.format(value.toInstant());
    }

    private static FileTime lastModified(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                return Files.getLastModifiedTime(path);
            }
        } catch (Exception ignored) {
            // Si no se puede leer el timestamp, se mantiene null.
        }
        return null;
    }

    private static FileTime lastModifiedRecursive(Path root) {
        if (root == null || !Files.exists(root)) {
            return null;
        }
        FileTime latest = null;
        try (var stream = Files.walk(root)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                FileTime current = lastModified(path);
                latest = maxTime(latest, current);
            }
        } catch (Exception ignored) {
            return latest;
        }
        return latest;
    }

    private static FileTime maxTime(FileTime current, FileTime candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.compareTo(current) > 0 ? candidate : current;
    }

    static SubmissionResult submitBundle(AppConfig config, HttpClient httpClient,
                                         BallotBundle bundle,
                                         String preferredAuxsid) throws Exception {
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path submissionDir = config.submissionsDir.resolve(runId);
        Files.createDirectories(submissionDir);

        ArrayList<String> events = new ArrayList<>();
        events.add(event("Inicio de emision. runId=" + runId));

        MixerDiscoveryCoordinator.LeaseContext lease = config.mixerCoordinator.ensureLease(httpClient, preferredAuxsid);
        String resolvedAuxsid = lease.auxsid();
        String serviceBaseUrl = lease.baseUrl();
        events.add(event("Mezcladora seleccionada: " + serviceBaseUrl));
        events.add(event("Handshake activo => station_id=" + lease.stationId()
                + " lease_id=" + lease.leaseId()
                + " expires_at=" + lease.expiresAt()));
        events.add(event("Auxsid operativo resuelto: " + resolvedAuxsid));
        events.add(event("Contexto de emision => requested_auxsid="
                + firstNonBlank(lease.requestedAuxsid(), "(vacio)")
                + " auxsid_changed=" + lease.auxsidChanged()
                + " accumulated=" + lease.accumulated()
                + " accumulated_from_auxsid=" + firstNonBlank(lease.accumulatedFromAuxsid(), "ninguno")));

        PublicKeyPayload publicKey = fetchPublicKeyPayload(httpClient, serviceBaseUrl, "native");
        if (!publicKey.hasActiveSession()) {
            throw new IllegalStateException("La mezcladora no reporta una sesion activa para recibir votos.");
        }
        byte[] publicKeyBytes = publicKey.contentBytes;
        String serviceSessionId = firstNonBlank(publicKey.sessionId, lease.sessionId());
        String serviceSessionName = firstNonBlank(publicKey.sessionName, lease.sessionName());
        String serviceSessionLabel = firstNonBlank(publicKey.sessionLabel, lease.sessionLabel());
        events.add(event("Llave publica nativa descargada. bytes=" + publicKeyBytes.length));
        events.add(event("Sesion remota => id=" + firstNonBlank(serviceSessionId, "n/d")
                + " name=" + firstNonBlank(serviceSessionName, "n/d")
                + " label=" + firstNonBlank(serviceSessionLabel, "n/d")));

        Path publicKeyPath = submissionDir.resolve("publicKey");
        Path plainVotesPath = submissionDir.resolve("plain_votes.txt");
        Path ciphertextsPath = submissionDir.resolve("ciphertexts_ext");
        Path stdoutPath = submissionDir.resolve("cifrador.stdout.log");
        Path stderrPath = submissionDir.resolve("cifrador.stderr.log");

        Files.write(publicKeyPath, publicKeyBytes);
        Files.writeString(plainVotesPath, bundle.bundleText(), StandardCharsets.UTF_8);
        events.add(event("Cedula canonica validada y serializada en plain_votes.txt"));

        ProcessBuilder pb = new ProcessBuilder(
                config.javaExePath().toString(),
                "-Djava.library.path=" + config.nativeLibDirPath(),
                "-jar",
                config.jarPath().toString(),
                publicKeyPath.toString(),
                plainVotesPath.toString(),
                ciphertextsPath.toString(),
                "-sw"
        );
        pb.directory(submissionDir.toFile());
        pb.redirectErrorStream(true);
        events.add(event("Invocando cifrador desacoplado del ejecutable."));

        Process process = pb.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = "";
        int exitCode = process.waitFor();

        Files.writeString(stdoutPath, stdout, StandardCharsets.UTF_8);
        Files.writeString(stderrPath, stderr, StandardCharsets.UTF_8);
        events.add(event("Proceso de cifrado finalizado. exitCode=" + exitCode));

        if (exitCode != 0) {
            throw new IllegalStateException("El cifrador retorno exitCode=" + exitCode
                    + ". runId=" + runId
                    + ". stdoutTail=" + tail(stdout));
        }
        if (!Files.exists(ciphertextsPath) || Files.size(ciphertextsPath) == 0L) {
            throw new IllegalStateException("No se genero ciphertexts_ext."
                    + " runId=" + runId
                    + " submissionDir=" + submissionDir
                    + " stdoutTail=" + tail(stdout));
        }

        String ciphertextsExt = Files.readString(ciphertextsPath, StandardCharsets.UTF_8);
        int ciphertextCount = countNonBlankLines(ciphertextsExt);
        events.add(event("Voto cifrado generado. registros=" + ciphertextCount));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("station_id", lease.stationId());
        payload.put("lease_id", lease.leaseId());
        payload.put("auxsid", resolvedAuxsid);
        payload.put("format", "native");
        if (!serviceSessionId.isBlank()) {
            payload.put("session_id", serviceSessionId);
        }
        if (!serviceSessionName.isBlank()) {
            payload.put("session_name", serviceSessionName);
        }
        payload.put("ciphertexts_ext", ciphertextsExt);
        payload.put("width", VERIFICATUM_WIDTH);
        events.add(event("POST " + serviceBaseUrl + "/api/ciphertexts"));
        events.add(event("curl -X POST \"" + serviceBaseUrl + "/api/ciphertexts\" "
                + "-H \"Content-Type: application/json\" "
                + "-d '{\"station_id\":\"" + lease.stationId()
                + "\",\"lease_id\":\"" + lease.leaseId()
                + "\",\"auxsid\":\"" + resolvedAuxsid
                + "\",\"format\":\"native\""
                + (!serviceSessionId.isBlank() ? ",\"session_id\":\"" + serviceSessionId + "\"" : "")
                + (!serviceSessionName.isBlank() ? ",\"session_name\":\"" + serviceSessionName + "\"" : "")
                + ",\"width\":" + VERIFICATUM_WIDTH
                + ",\"ciphertexts_ext\":\"" + summarizeCiphertexts(ciphertextsExt) + "\"}'"));
        events.add(event("Payload => {\"station_id\":\"" + lease.stationId()
                + "\",\"lease_id\":\"" + lease.leaseId()
                + "\",\"auxsid\":\"" + resolvedAuxsid
                + "\",\"format\":\"native\""
                + (!serviceSessionId.isBlank() ? ",\"session_id\":\"" + serviceSessionId + "\"" : "")
                + (!serviceSessionName.isBlank() ? ",\"session_name\":\"" + serviceSessionName + "\"" : "")
                + ",\"width\":" + VERIFICATUM_WIDTH
                + ",\"ciphertexts_ext_lines\":" + ciphertextCount
                + ",\"ciphertexts_ext_bytes\":" + ciphertextsExt.getBytes(StandardCharsets.UTF_8).length
                + ",\"ciphertexts_ext_head\":\"" + summarizeCiphertexts(ciphertextsExt) + "\"}"));
        events.add(event("Remitiendo ciphertexts_ext al servicio remoto con format=native."));

        String receiptRaw = postJson(httpClient,
                URI.create(serviceBaseUrl + "/api/ciphertexts"),
                JsonUtil.toJson(payload));
        boolean receiptAccepted = extractJsonBoolean(receiptRaw, "ok");
        String serviceResolvedAuxsid = firstNonBlank(extractJsonString(receiptRaw, "resolved_auxsid"), resolvedAuxsid);
        events.add(event("Respuesta API => validated=" + extractJsonBoolean(receiptRaw, "validated")
                + " handshake_validated=" + extractJsonBoolean(receiptRaw, "handshake_validated")
                + " format_resolved=" + firstNonBlank(extractJsonString(receiptRaw, "format_resolved"), "native")
                + " party_validated=" + firstNonBlank(extractJsonString(receiptRaw, "party_validated"), "party01")
                + " accumulated=" + extractJsonBoolean(receiptRaw, "accumulated")
                + " session=" + firstNonBlank(extractJsonString(receiptRaw, "session"), "no-informada")));
        events.add(event("Servicio remoto respondio ok=" + receiptAccepted
                + " auxsid=" + serviceResolvedAuxsid));

        Map<String, Object> emissionContext = new LinkedHashMap<>();
        emissionContext.put("service_base_url", serviceBaseUrl);
        emissionContext.put("session_id", serviceSessionId);
        emissionContext.put("session_name", serviceSessionName);
        emissionContext.put("session_label", serviceSessionLabel);
        emissionContext.put("requested_auxsid", lease.requestedAuxsid());
        emissionContext.put("resolved_auxsid", resolvedAuxsid);
        emissionContext.put("auxsid_changed", lease.auxsidChanged());
        emissionContext.put("accumulated", lease.accumulated());
        emissionContext.put("accumulated_from_auxsid", lease.accumulatedFromAuxsid());
        emissionContext.put("lease_id", lease.leaseId());
        emissionContext.put("station_id", lease.stationId());
        Files.writeString(submissionDir.resolve("emission-context.json"),
                JsonUtil.toJson(emissionContext), StandardCharsets.UTF_8);
        Files.writeString(submissionDir.resolve("receipt.json"), receiptRaw, StandardCharsets.UTF_8);

        return new SubmissionResult(
                runId,
                resolvedAuxsid,
                serviceResolvedAuxsid,
                serviceSessionId,
                serviceSessionName,
                serviceSessionLabel,
                extractJsonBoolean(receiptRaw, "accumulated"),
                firstNonBlank(extractJsonString(receiptRaw, "accumulated_from_auxsid"),
                        lease.accumulatedFromAuxsid()),
                receiptAccepted,
                bundle,
                submissionDir,
                publicKeyPath,
                plainVotesPath,
                ciphertextsPath,
                stdoutPath,
                stderrPath,
                stdout,
                stderr,
                "",
                receiptRaw,
                events
        );
    }

    static BallotBundle buildBundle(Map<String, String> form) {
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

        String[] senatorsNationalPreferentials =
                normalizeDistinctPreferentials("senadores_nacionales", senatorsNationalPv1, senatorsNationalPv2);
        String[] deputiesPreferentials =
                normalizeDistinctPreferentials("diputados_regionales", deputiesPv1, deputiesPv2);
        String[] andeanPreferentials =
                normalizeDistinctPreferentials("parlamento_andino", andeanPv1, andeanPv2);
        senatorsNationalPv1 = senatorsNationalPreferentials[0];
        senatorsNationalPv2 = senatorsNationalPreferentials[1];
        deputiesPv1 = deputiesPreferentials[0];
        deputiesPv2 = deputiesPreferentials[1];
        andeanPv1 = andeanPreferentials[0];
        andeanPv2 = andeanPreferentials[1];

        ArrayList<String> lines = new ArrayList<>();
        lines.add("0100" + presidentialParty + "0000");
        lines.add("0200" + senatorsNationalParty + senatorsNationalPv1 + senatorsNationalPv2);
        lines.add("03" + districtCode + senatorsRegionalParty + senatorsRegionalPv1 + "00");
        lines.add("04" + districtCode + deputiesParty + deputiesPv1 + deputiesPv2);
        lines.add("0500" + andeanParty + andeanPv1 + andeanPv2);
        return new BallotBundle(districtCode, lines);
    }

    private static void proxyServiceJson(HttpExchange exchange, HttpClient httpClient, String url)
            throws java.io.IOException {
        try {
            HttpUtil.ensureMethod(exchange, "GET");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            ensureHttpOk(response.statusCode(), "No se pudo consultar el servicio externo.");
            HttpUtil.sendBytes(exchange, 200, "application/json; charset=utf-8", response.body());
        } catch (ConnectException ex) {
            HttpUtil.sendError(exchange, 502,
                    new IllegalStateException("No se pudo conectar con el servicio remoto en " + url, ex));
        } catch (Exception ex) {
            HttpUtil.sendError(exchange, 500, ex);
        }
    }

    private static String activeServiceBaseUrl(AppConfig config) {
        return firstNonBlank(config.mixerCoordinator.activeBaseUrl(), config.serviceBaseUrl);
    }

    private static String resolvePublicHost(String bindHost, String configuredPublicHost) {
        if (configuredPublicHost != null && !configuredPublicHost.isBlank()) {
            return configuredPublicHost.trim();
        }
        if (bindHost != null && !bindHost.isBlank()
                && !"0.0.0.0".equals(bindHost)
                && !"::".equals(bindHost)
                && !"*".equals(bindHost)) {
            if ("localhost".equalsIgnoreCase(bindHost)) {
                return "127.0.0.1";
            }
            return bindHost;
        }
        String lanHost = findPreferredLanIpv4();
        return firstNonBlank(lanHost, "127.0.0.1");
    }

    private static String findPreferredLanIpv4() {
        try {
            for (NetworkInterface networkInterface : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                String name = firstNonBlank(
                        firstNonBlank(networkInterface.getDisplayName(), networkInterface.getName()),
                        ""
                )
                        .toLowerCase();
                if (name.contains("hyper-v")
                        || name.contains("vmware")
                        || name.contains("virtualbox")
                        || name.contains("docker")
                        || name.contains("wsl")
                        || name.contains("bluetooth")
                        || name.contains("loopback")) {
                    continue;
                }
                for (InetAddress address : java.util.Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address ipv4
                            && !ipv4.isLoopbackAddress()
                            && !ipv4.isLinkLocalAddress()
                            && ipv4.isSiteLocalAddress()) {
                        return ipv4.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            return null;
        }
        return null;
    }

    private static byte[] fetchPublicKey(HttpClient httpClient, String serviceBaseUrl, String format) throws Exception {
        return fetchPublicKeyPayload(httpClient, serviceBaseUrl, format).contentBytes;
    }

    private static PublicKeyPayload fetchPublicKeyPayload(HttpClient httpClient, String serviceBaseUrl, String format)
            throws Exception {
        String normalizedFormat = firstNonBlank(format, "native");
        String url = serviceBaseUrl + "/api/public-key";
        if (!"native".equalsIgnoreCase(normalizedFormat)) {
            url += "?format=" + HttpUtil.urlEncode(normalizedFormat);
        }
        HttpRequest publicKeyRequest = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = httpClient.send(publicKeyRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureHttpOk(response.statusCode(), "No se pudo obtener la llave publica.");
        String body = response.body();
        String contentHex = extractJsonString(body, "content");
        if (!contentHex.isBlank()) {
            return new PublicKeyPayload(
                    decodeHex(contentHex),
                    extractJsonString(body, "session_id"),
                    extractJsonString(body, "session_name"),
                    extractJsonString(body, "session_label"),
                    extractJsonString(body, "election_name"),
                    body
            );
        }
        return new PublicKeyPayload(
                body.getBytes(StandardCharsets.UTF_8),
                extractJsonString(body, "session_id"),
                extractJsonString(body, "session_name"),
                extractJsonString(body, "session_label"),
                extractJsonString(body, "election_name"),
                body
        );
    }

    private static String fetchAuxsidsRaw(HttpClient httpClient, AppConfig config) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.serviceBaseUrl + "/api/auxsids"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureHttpOk(response.statusCode(), "No se pudo consultar auxsids.");
        return response.body();
    }

    private static String fetchStateRaw(HttpClient httpClient, AppConfig config) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.serviceBaseUrl + "/api/state"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureHttpOk(response.statusCode(), "No se pudo consultar el estado de la GUI Verificatum.");
        return response.body();
    }

    private static String postJson(HttpClient httpClient, URI uri, String jsonPayload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureHttpOk(response.statusCode(), "No se pudo enviar ciphertexts_ext.");
        return response.body();
    }

    private static void ensureHttpOk(int statusCode, String message) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(message + " HTTP " + statusCode);
        }
    }

    private static String resolveAuxsid(String preferredAuxsid, String auxsidsRaw) {
        String candidate = normalizeAuxsid(preferredAuxsid);
        if (!candidate.isBlank()) {
            return candidate;
        }
        candidate = normalizeAuxsid(extractJsonString(auxsidsRaw, "suggested_auxsid"));
        if (!candidate.isBlank()) {
            return candidate;
        }
        candidate = normalizeAuxsid(extractJsonString(auxsidsRaw, "next_shuffle"));
        if (!candidate.isBlank()) {
            return candidate;
        }
        return "default";
    }

    private static String resolveServiceSession(String stateRaw) {
        String[] candidateKeys = new String[]{
                "session",
                "session_id",
                "session_name",
                "active_session",
                "activeSession",
                "current_session",
                "currentSession"
        };
        for (String key : candidateKeys) {
            String value = extractJsonString(stateRaw, key);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String resolveInactiveReason(Map<String, Object> payload) {
        if (!Boolean.TRUE.equals(payload.get("serviceStateOk"))) {
            return "la GUI de mezcladora no responde.";
        }
        if (!Boolean.TRUE.equals(payload.get("serviceOk"))) {
            return "no se pudo resolver auxsid operativo.";
        }
        if (!Boolean.TRUE.equals(payload.get("publicKeyOk"))) {
            return "no se pudo obtener la llave publica activa.";
        }
        return "la mezcladora no reporta una sesion activa.";
    }

    private static String extractJsonString(String json, String key) {
        Matcher matcher = JSON_STRING_FIELD.matcher(json == null ? "" : json);
        while (matcher.find()) {
            String currentKey = matcher.group(1);
            String rawValue = matcher.group(2);
            if (!key.equals(currentKey) || rawValue == null || "null".equals(rawValue)) {
                continue;
            }
            return matcher.group(3);
        }
        return "";
    }

    private static boolean extractJsonBoolean(String json, String key) {
        Matcher matcher = JSON_BOOLEAN_FIELD.matcher(json == null ? "" : json);
        while (matcher.find()) {
            if (key.equals(matcher.group(1))) {
                return Boolean.parseBoolean(matcher.group(2));
            }
        }
        return false;
    }

    private static String normalizeAuxsid(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (!trimmed.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("auxsid invalido: " + trimmed);
        }
        return trimmed;
    }

    private static byte[] decodeHex(String value) {
        String hex = value == null ? "" : value.trim();
        if (hex.isBlank()) {
            return new byte[0];
        }
        if ((hex.length() % 2) != 0) {
            throw new IllegalArgumentException("Hex invalido para la llave publica.");
        }
        byte[] decoded = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Hex invalido para la llave publica.");
            }
            decoded[i / 2] = (byte) ((high << 4) + low);
        }
        return decoded;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String[] normalizeDistinctPreferentials(String electionCode, String pv1, String pv2) {
        if (!"00".equals(pv1) && pv1.equals(pv2)) {
            return new String[]{pv1, "00"};
        }
        return new String[]{pv1, pv2};
    }

    private static String normalizeRequiredCode(String field, String value) {
        String normalized = normalizeOptionalCode(value);
        if ("00".equals(normalized)) {
            throw new IllegalArgumentException("Campo obligatorio faltante: " + field);
        }
        return normalized;
    }

    private static String normalizeOptionalCode(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return "00";
        }
        if (!trimmed.matches("\\d{2}")) {
            throw new IllegalArgumentException("Codigo invalido, se esperaban 2 digitos: " + trimmed);
        }
        return trimmed;
    }

    private static int countNonBlankLines(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : value.replace("\r", "").split("\n")) {
            if (!line.isBlank()) {
                count += 1;
            }
        }
        return count;
    }

    private static String event(String message) {
        return Instant.now().toString() + " | " + message;
    }

    private static String summarizeCiphertexts(String ciphertextsExt) {
        if (ciphertextsExt == null || ciphertextsExt.isBlank()) {
            return "";
        }
        String compact = ciphertextsExt.replace("\r", "").replace("\n", "");
        int maxLength = Math.min(96, compact.length());
        return compact.substring(0, maxLength) + (compact.length() > maxLength ? "..." : "");
    }

    static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void json(HttpExchange exchange, JsonSupplier supplier)
            throws java.io.IOException {
        try {
            HttpUtil.sendJson(exchange, 200, supplier.get());
        } catch (ConnectException ex) {
            HttpUtil.sendError(exchange, 502,
                    new IllegalStateException("No se pudo conectar con el servicio remoto en " + DEFAULT_SERVICE_BASE_URL, ex));
        } catch (IllegalArgumentException ex) {
            HttpUtil.sendError(exchange, 400, ex);
        } catch (Exception ex) {
            HttpUtil.sendError(exchange, 500, ex);
        }
    }

    private static String readStringProperty(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String stationProperty(String key, String legacyKey, String fallback) {
        String generic = readStringProperty("votante.station." + key, "");
        if (!generic.isBlank()) {
            return generic;
        }
        return readStringProperty(legacyKey, fallback);
    }

    private static String defaultJavaBinaryName() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return "java.exe";
        }
        return "java";
    }

    static Path resolveRootDir() {
        String configured = readStringProperty("votante.root", readStringProperty("votante.windows.root", ""));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r", "");
        String[] lines = normalized.split("\n");
        int from = Math.max(0, lines.length - 10);
        return String.join("\n", List.of(lines).subList(from, lines.length));
    }

    private interface JsonSupplier {
        Object get() throws Exception;
    }

    static record AppConfig(
            Path rootDir,
            Path publicDir,
            Path runtimeDir,
            Path submissionsDir,
            Path catalogPath,
            Path schemaPath,
            Path cifradorDir,
            String bindHost,
            String publicHost,
            int port,
            String serviceBaseUrl,
            String defaultAuxsid,
            String stationId,
            String interfaceDisplayName,
            String cipherDisplayName,
            String cipherRngSupport,
            String cifradorJarName,
            String cifradorNativeSubdir,
            String javaBinName,
            VoterProfile voterProfile,
            MixerDiscoveryCoordinator mixerCoordinator
    ) {
        String localBaseUrl() {
            if ("0.0.0.0".equals(bindHost) || "::".equals(bindHost) || "*".equals(bindHost)) {
                return "http://127.0.0.1:" + port;
            }
            if ("localhost".equalsIgnoreCase(bindHost)) {
                return "http://127.0.0.1:" + port;
            }
            return "http://" + bindHost + ":" + port;
        }

        String publicBaseUrl() {
            return "http://" + publicHost + ":" + port;
        }

        Path javaExePath() {
            Path currentJavaHome = Path.of(System.getProperty("java.home"));
            return currentJavaHome.resolve("bin").resolve(javaBinName);
        }

        Path nativeLibDirPath() {
            return cifradorDir.resolve("libs").resolve(cifradorNativeSubdir);
        }

        Path jarPath() {
            return cifradorDir.resolve("app").resolve(cifradorJarName);
        }
    }

    static record VoterProfile(
            String fullName,
            String dni,
            String electoralDistrict,
            String districtCode,
            String local,
            String mesa,
            String order,
            String ubigeo,
            String condition,
            String emissionWindow
    ) {
        static VoterProfile demo() {
            return new VoterProfile(
                    "Electo Votario Sufraguez Urnález",
                    "42777333",
                    "LIMA METROPOLITANA",
                    "02",
                    "I.E. 7086 LOS JARDINES",
                    "047612",
                    "218",
                    "150101",
                    "HABIL PARA SUFRAGAR",
                    "08:00 - 16:00"
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fullName", fullName);
            payload.put("dni", dni);
            payload.put("electoralDistrict", electoralDistrict);
            payload.put("districtCode", districtCode);
            payload.put("local", local);
            payload.put("mesa", mesa);
            payload.put("order", order);
            payload.put("ubigeo", ubigeo);
            payload.put("condition", condition);
            payload.put("emissionWindow", emissionWindow);
            return payload;
        }
    }

    static record PublicKeyPayload(
            byte[] contentBytes,
            String sessionId,
            String sessionName,
            String sessionLabel,
            String electionName,
            String rawJson
    ) {
        boolean hasKeyMaterial() {
            return contentBytes != null && contentBytes.length > 0;
        }

        boolean hasActiveSession() {
            return !firstNonBlank(sessionId, firstNonBlank(sessionName, sessionLabel)).isBlank();
        }
    }

    static record BallotBundle(String districtCode, List<String> lines) {
        String bundleText() {
            return String.join("\n", lines) + "\n";
        }

        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("districtCode", districtCode);
            payload.put("lineCount", lines.size());
            payload.put("lines", lines);
            payload.put("bundleText", bundleText());
            return payload;
        }
    }

    static record SubmissionResult(
            String runId,
            String auxsid,
            String serviceResolvedAuxsid,
            String serviceSessionId,
            String serviceSessionName,
            String serviceSessionLabel,
            boolean serviceAccumulated,
            String serviceAccumulatedFromAuxsid,
            boolean receiptAccepted,
            BallotBundle bundle,
            Path submissionDir,
            Path publicKeyPath,
            Path plainVotesPath,
            Path ciphertextsPath,
            Path stdoutPath,
            Path stderrPath,
            String stdout,
            String stderr,
            String auxsidsRaw,
            String receiptRaw,
            List<String> events
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("auxsid", auxsid);
            payload.put("serviceResolvedAuxsid", serviceResolvedAuxsid);
            payload.put("serviceSessionId", serviceSessionId);
            payload.put("serviceSessionName", serviceSessionName);
            payload.put("serviceSessionLabel", serviceSessionLabel);
            payload.put("serviceAccumulated", serviceAccumulated);
            payload.put("serviceAccumulatedFromAuxsid", serviceAccumulatedFromAuxsid);
            payload.put("receiptAccepted", receiptAccepted);
            payload.put("bundle", bundle.toMap());
            payload.put("submissionDir", submissionDir.toString());
            payload.put("publicKeyPath", publicKeyPath.toString());
            payload.put("plainVotesPath", plainVotesPath.toString());
            payload.put("ciphertextsPath", ciphertextsPath.toString());
            payload.put("stdoutPath", stdoutPath.toString());
            payload.put("stderrPath", stderrPath.toString());
            payload.put("stdoutTail", tail(stdout));
            payload.put("stderrTail", tail(stderr));
            payload.put("auxsidsRaw", auxsidsRaw);
            payload.put("receiptRaw", receiptRaw);
            payload.put("events", events);
            payload.put("monitorText", String.join("\n", events));
            payload.put("width", VERIFICATUM_WIDTH);
            payload.put("publicKeyFormat", "native");
            payload.put("ciphertextsFormat", "native");
            payload.put("voteSchemaVersion", SCHEMA_VERSION);
            return payload;
        }
    }
}
