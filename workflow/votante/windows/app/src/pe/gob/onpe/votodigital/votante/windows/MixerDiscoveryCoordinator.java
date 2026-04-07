package pe.gob.onpe.votodigital.votante.windows;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MixerDiscoveryCoordinator {

    private static final Pattern JSON_STRING_FIELD =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(null|\"([^\"]*)\")");
    private static final Pattern JSON_BOOLEAN_FIELD =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(true|false)");
    private static final int DEFAULT_PORT = 7040;
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofMillis(250);
    private static final Duration CONFIGURED_DISCOVERY_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LEASE_RENEW_MARGIN = Duration.ofSeconds(15);
    private static final Duration DISCOVERY_CACHE_TTL = Duration.ofMinutes(5);
    private static final ExecutorService DISCOVERY_EXECUTOR = Executors.newFixedThreadPool(96);
    private static final int MAX_SUBNETS_TO_SCAN = 2;

    private final String configuredBaseUrl;
    private final String stationId;
    private LeaseContext currentLease;
    private List<String> cachedResponsiveBaseUrls = List.of();
    private Instant lastSweepAt = Instant.EPOCH;

    MixerDiscoveryCoordinator(String configuredBaseUrl, String stationId) {
        this.configuredBaseUrl = trimTrailingSlash(configuredBaseUrl);
        this.stationId = stationId;
    }

    synchronized BootstrapSnapshot bootstrap(HttpClient httpClient, String preferredAuxsid) {
        try {
            String requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid);
            String expectedSessionId = currentLease == null ? "" : currentLease.sessionId();
            DiscoveryInfo discovery = findBestDiscovery(httpClient, expectedSessionId);
            if (discovery == null) {
                return BootstrapSnapshot.inactive(
                        firstNonBlank(activeBaseUrl(), configuredBaseUrl),
                        "",
                        "No se encontro una mezcladora activa con sesion compatible en la red local."
                );
            }
            EmissionContextInfo emissionContext = fetchEmissionContext(httpClient, discovery, requestedAuxsid);
            if (!emissionContext.hasContext()) {
                return BootstrapSnapshot.inactive(
                        discovery.baseUrl(),
                        "",
                        "La mezcladora no confirmo el contexto de emision por /api/emission-context."
                );
            }
            String resolvedAuxsid = resolvedOperationalAuxsid(emissionContext);
            LeaseContext reusableLease = null;
            if (currentLease != null
                    && discovery.baseUrl().equals(currentLease.baseUrl())
                    && discovery.sessionId().equals(currentLease.sessionId())
                    && !currentLease.isExpired()) {
                reusableLease = currentLease;
            }
            if (!emissionContext.acceptingVotes()) {
                return BootstrapSnapshot.busy(discovery, reusableLease, resolvedAuxsid, emissionContext);
            }
            PublicKeyInfo publicKey = fetchPublicKeyInfo(httpClient, discovery.baseUrl(), "native");
            return BootstrapSnapshot.active(discovery, reusableLease, publicKey, resolvedAuxsid, emissionContext);
        } catch (Exception ex) {
            return BootstrapSnapshot.inactive(
                    firstNonBlank(activeBaseUrl(), configuredBaseUrl),
                    "",
                    ex.getMessage()
            );
        }
    }

    synchronized LeaseContext ensureLease(HttpClient httpClient, String preferredAuxsid) throws Exception {
        return selectLease(httpClient, preferredAuxsid, true);
    }

    synchronized String activeBaseUrl() {
        return currentLease == null ? "" : currentLease.baseUrl();
    }

    synchronized String activeSessionId() {
        return currentLease == null ? "" : currentLease.sessionId();
    }

    private LeaseContext selectLease(HttpClient httpClient, String preferredAuxsid, boolean forceRenew)
            throws Exception {
        String requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid);
        LeaseContext lease = currentLease;
        if (lease != null) {
            DiscoveryInfo currentDiscovery = tryDiscovery(httpClient, lease.baseUrl(), lease.sessionId(), CONFIGURED_DISCOVERY_TIMEOUT);
            if (currentDiscovery != null && currentDiscovery.acceptingVotes()) {
                if (!forceRenew && !lease.isExpiringSoon()) {
                    currentLease = lease.withDiscovery(currentDiscovery);
                    return currentLease;
                }
                HandshakeInfo renewal = tryHandshake(httpClient, currentDiscovery, requestedAuxsid);
                if (renewal != null && renewal.accepted()) {
                    EmissionContextInfo emissionContext =
                            fetchConfirmedEmissionContext(httpClient, currentDiscovery, renewal.requestedAuxsid());
                    currentLease = LeaseContext.from(currentDiscovery, renewal, emissionContext);
                    rememberCandidate(currentDiscovery.baseUrl());
                    return currentLease;
                }
            }
            currentLease = null;
        }

        String expectedSessionId = lease == null ? "" : lease.sessionId();
        for (String candidate : prioritizedCandidates()) {
            DiscoveryInfo discovery = tryDiscovery(httpClient, candidate, expectedSessionId, CONFIGURED_DISCOVERY_TIMEOUT);
            if (discovery == null) {
                continue;
            }
            HandshakeInfo handshake = tryHandshake(httpClient, discovery, requestedAuxsid);
            if (handshake != null && handshake.accepted()) {
                EmissionContextInfo emissionContext =
                        fetchConfirmedEmissionContext(httpClient, discovery, handshake.requestedAuxsid());
                currentLease = LeaseContext.from(discovery, handshake, emissionContext);
                rememberCandidate(discovery.baseUrl());
                return currentLease;
            }
        }

        for (DiscoveryInfo discovery : sweepLocalNetwork(httpClient, expectedSessionId)) {
            HandshakeInfo handshake = tryHandshake(httpClient, discovery, requestedAuxsid);
            if (handshake != null && handshake.accepted()) {
                EmissionContextInfo emissionContext =
                        fetchConfirmedEmissionContext(httpClient, discovery, handshake.requestedAuxsid());
                currentLease = LeaseContext.from(discovery, handshake, emissionContext);
                rememberCandidate(discovery.baseUrl());
                return currentLease;
            }
        }

        throw new IllegalStateException(
                "No se encontro una mezcladora activa con sesion compatible en la red local."
        );
    }

    private DiscoveryInfo findBestDiscovery(HttpClient httpClient, String expectedSessionId) {
        DiscoveryInfo busyCandidate = null;
        for (String candidate : prioritizedCandidates()) {
            DiscoveryInfo discovery = tryDiscovery(httpClient, candidate, expectedSessionId, CONFIGURED_DISCOVERY_TIMEOUT);
            if (discovery == null) {
                continue;
            }
            if (discovery.acceptingVotes()) {
                return discovery;
            }
            if (busyCandidate == null) {
                busyCandidate = discovery;
            }
        }
        for (DiscoveryInfo discovery : sweepLocalNetwork(httpClient, expectedSessionId)) {
            if (discovery.acceptingVotes()) {
                return discovery;
            }
            if (busyCandidate == null) {
                busyCandidate = discovery;
            }
        }
        return busyCandidate;
    }

    private List<String> prioritizedCandidates() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (currentLease != null) {
            urls.add(currentLease.baseUrl());
        }
        if (!configuredBaseUrl.isBlank()) {
            urls.add(configuredBaseUrl);
        }
        urls.addAll(cachedResponsiveBaseUrls);
        return new ArrayList<>(urls);
    }

    private List<DiscoveryInfo> sweepLocalNetwork(HttpClient httpClient, String expectedSessionId) {
        if (!cachedResponsiveBaseUrls.isEmpty()
                && Duration.between(lastSweepAt, Instant.now()).compareTo(DISCOVERY_CACHE_TTL) < 0) {
            ArrayList<DiscoveryInfo> cached = new ArrayList<>();
            for (String baseUrl : cachedResponsiveBaseUrls) {
                DiscoveryInfo discovery = tryDiscovery(httpClient, baseUrl, expectedSessionId);
                if (discovery != null) {
                    cached.add(discovery);
                }
            }
            return cached;
        }

        List<String> candidates = subnetCandidates();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Callable<DiscoveryInfo>> tasks = new ArrayList<>();
        for (String candidate : candidates) {
            tasks.add(() -> tryDiscovery(httpClient, candidate, expectedSessionId));
        }
        ArrayList<DiscoveryInfo> discoveries = new ArrayList<>();
        try {
            List<Future<DiscoveryInfo>> futures = DISCOVERY_EXECUTOR.invokeAll(tasks);
            for (Future<DiscoveryInfo> future : futures) {
                try {
                    DiscoveryInfo discovery = future.get();
                    if (discovery != null) {
                        discoveries.add(discovery);
                    }
                } catch (ExecutionException ex) {
                    // ignore candidate failures
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        discoveries.sort((left, right) -> left.baseUrl().compareTo(right.baseUrl()));
        cachedResponsiveBaseUrls = discoveries.stream().map(DiscoveryInfo::baseUrl).toList();
        lastSweepAt = Instant.now();
        return discoveries;
    }

    private DiscoveryInfo tryDiscovery(HttpClient httpClient, String baseUrl, String expectedSessionId) {
        return tryDiscovery(httpClient, baseUrl, expectedSessionId, DISCOVERY_TIMEOUT);
    }

    private DiscoveryInfo tryDiscovery(HttpClient httpClient, String baseUrl, String expectedSessionId, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            String discoveryUrl = trimTrailingSlash(baseUrl) + "/api/discovery";
            HttpRequest request = HttpRequest.newBuilder(URI.create(discoveryUrl))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String body = response.body();
            boolean ok = extractJsonBoolean(body, "ok");
            boolean hasActiveSession = extractJsonBoolean(body, "has_active_session");
            boolean keygenReady = extractJsonBoolean(body, "keygen_ready");
            boolean acceptingVotes = extractJsonBoolean(body, "accepting_votes");
            String sessionId = extractJsonString(body, "session_id");
            if (!ok || !hasActiveSession || !keygenReady) {
                return null;
            }
            if (!expectedSessionId.isBlank() && !expectedSessionId.equals(sessionId)) {
                return null;
            }
            return new DiscoveryInfo(
                    canonicalBaseUrl(baseUrl, extractJsonString(body, "api_url")),
                    extractJsonString(body, "instance_id"),
                    extractJsonString(body, "hostname"),
                    firstNonBlank(extractJsonString(body, "public_host"), hostFromBaseUrl(baseUrl)),
                    canonicalEndpointUrl(baseUrl, extractJsonString(body, "handshake_url"), "/api/handshake"),
                    canonicalEndpointUrl(baseUrl, extractJsonString(body, "public_key_url"), "/api/public-key"),
                    canonicalEndpointUrl(baseUrl, extractJsonString(body, "ciphertexts_url"), "/api/ciphertexts"),
                    canonicalEndpointUrl(baseUrl, extractJsonString(body, "emission_context_url"), "/api/emission-context"),
                    extractJsonInt(body, "handshake_ttl_seconds", 90),
                    sessionId,
                    extractJsonString(body, "session_name"),
                    extractJsonString(body, "session_label"),
                    extractJsonString(body, "election_name"),
                    extractJsonString(body, "sid"),
                    acceptingVotes,
                    extractJsonString(body, "current_operation")
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private HandshakeInfo tryHandshake(HttpClient httpClient, DiscoveryInfo discovery, String preferredAuxsid) {
        try {
            String requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("station_id", stationId);
            payload.put("session_id", discovery.sessionId());
            payload.put("session_name", discovery.sessionName());
            if (!requestedAuxsid.isBlank()) {
                payload.put("auxsid", requestedAuxsid);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(discovery.handshakeUrl()))
                    .timeout(HANDSHAKE_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String body = response.body();
            return new HandshakeInfo(
                    extractJsonBoolean(body, "ok"),
                    extractJsonBoolean(body, "accepted"),
                    extractJsonString(body, "reason"),
                    firstNonBlank(extractJsonString(body, "station_id"), stationId),
                    firstNonBlank(extractJsonString(body, "requested_auxsid"), requestedAuxsid),
                    extractJsonString(body, "lease_id"),
                    parseInstant(extractJsonString(body, "expires_at")),
                    firstNonBlank(extractJsonString(body, "session_id"), discovery.sessionId()),
                    firstNonBlank(extractJsonString(body, "session_name"), discovery.sessionName()),
                    firstNonBlank(extractJsonString(body, "session_label"), discovery.sessionLabel()),
                    firstNonBlank(extractJsonString(body, "election_name"), discovery.electionName())
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private EmissionContextInfo fetchEmissionContext(HttpClient httpClient, DiscoveryInfo discovery, String preferredAuxsid) {
        String requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid);
        try {
            String url = discovery.emissionContextUrl();
            if (!requestedAuxsid.isBlank()) {
                url += "?auxsid=" + requestedAuxsid;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(CONFIGURED_DISCOVERY_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return EmissionContextInfo.empty(discovery, requestedAuxsid);
            }
            String body = response.body();
            return new EmissionContextInfo(
                    extractJsonBoolean(body, "ok"),
                    firstNonBlank(extractJsonString(body, "session_id"), discovery.sessionId()),
                    firstNonBlank(extractJsonString(body, "session_name"), discovery.sessionName()),
                    firstNonBlank(extractJsonString(body, "session_label"), discovery.sessionLabel()),
                    firstNonBlank(extractJsonString(body, "election_name"), discovery.electionName()),
                    firstNonBlank(extractJsonString(body, "sid"), discovery.sid()),
                    firstNonBlank(extractJsonString(body, "requested_auxsid"), requestedAuxsid),
                    extractJsonString(body, "resolved_auxsid"),
                    extractJsonString(body, "auxsid"),
                    extractJsonBoolean(body, "auxsid_changed"),
                    extractJsonBoolean(body, "accumulated"),
                    extractJsonString(body, "accumulated_from_auxsid"),
                    extractJsonBoolean(body, "accepting_votes")
            );
        } catch (Exception ex) {
            return EmissionContextInfo.empty(discovery, requestedAuxsid);
        }
    }

    private EmissionContextInfo fetchConfirmedEmissionContext(HttpClient httpClient,
                                                              DiscoveryInfo discovery,
                                                              String preferredAuxsid) {
        EmissionContextInfo emissionContext = fetchEmissionContext(httpClient, discovery, preferredAuxsid);
        if (!emissionContext.hasContext()) {
            throw new IllegalStateException("La mezcladora no confirmo el contexto de emision por /api/emission-context.");
        }
        if (resolvedOperationalAuxsid(emissionContext).isBlank()) {
            throw new IllegalStateException("La mezcladora no devolvio un auxsid operativo valido por /api/emission-context.");
        }
        if (!emissionContext.acceptingVotes()) {
            throw new IllegalStateException("La mezcladora no esta aceptando votos en este momento.");
        }
        return emissionContext;
    }

    private PublicKeyInfo fetchPublicKeyInfo(HttpClient httpClient, String baseUrl, String format) throws Exception {
        String suffix = "native".equalsIgnoreCase(firstNonBlank(format, "native")) ? "" : "?format=" + format;
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(baseUrl) + "/api/public-key" + suffix))
                .timeout(HANDSHAKE_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("No se pudo obtener la llave publica. HTTP " + response.statusCode());
        }
        String body = response.body();
        String contentHex = extractJsonString(body, "content");
        byte[] bytes = contentHex.isBlank()
                ? body.getBytes(StandardCharsets.UTF_8)
                : decodeHex(contentHex);
        return new PublicKeyInfo(
                bytes,
                extractJsonString(body, "session_id"),
                extractJsonString(body, "session_name"),
                extractJsonString(body, "session_label"),
                extractJsonString(body, "election_name"),
                body
        );
    }

    private void rememberCandidate(String baseUrl) {
        LinkedList<String> updated = new LinkedList<>(cachedResponsiveBaseUrls);
        updated.remove(baseUrl);
        updated.addFirst(baseUrl);
        cachedResponsiveBaseUrls = List.copyOf(updated);
        lastSweepAt = Instant.now();
    }

    private List<String> subnetCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        int port = configuredPort();
        LinkedHashSet<String> preferredSubnets = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                if (isLowPriorityInterface(networkInterface)) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address ipv4) || !ipv4.isSiteLocalAddress()) {
                        continue;
                    }
                    byte[] octets = ipv4.getAddress();
                    preferredSubnets.add(String.format("%d.%d.%d",
                            octets[0] & 0xff,
                            octets[1] & 0xff,
                            octets[2] & 0xff));
                    if (preferredSubnets.size() >= MAX_SUBNETS_TO_SCAN) {
                        break;
                    }
                }
                if (preferredSubnets.size() >= MAX_SUBNETS_TO_SCAN) {
                    break;
                }
            }
        } catch (Exception ex) {
            return Collections.emptyList();
        }
        for (String subnet : preferredSubnets) {
            String[] parts = subnet.split("\\.");
            for (int host = 1; host <= 254; host++) {
                String candidate = String.format("http://%s.%d:%d", subnet, host, port);
                candidates.add(candidate);
            }
        }
        for (String prioritized : prioritizedCandidates()) {
            candidates.remove(prioritized);
        }
        return new ArrayList<>(candidates);
    }

    private boolean isLowPriorityInterface(NetworkInterface networkInterface) {
        String descriptor = (networkInterface.getDisplayName() + " " + networkInterface.getName()).toLowerCase();
        return descriptor.contains("virtual")
                || descriptor.contains("vmware")
                || descriptor.contains("hyper-v")
                || descriptor.contains("vethernet")
                || descriptor.contains("docker")
                || descriptor.contains("wsl")
                || descriptor.contains("loopback")
                || descriptor.contains("bluetooth");
    }

    private int configuredPort() {
        try {
            return URI.create(configuredBaseUrl).getPort() > 0 ? URI.create(configuredBaseUrl).getPort() : DEFAULT_PORT;
        } catch (Exception ex) {
            return DEFAULT_PORT;
        }
    }

    private static String hostFromBaseUrl(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String extractJsonString(String json, String key) {
        Matcher matcher = JSON_STRING_FIELD.matcher(json == null ? "" : json);
        while (matcher.find()) {
            if (key.equals(matcher.group(1)) && matcher.group(2) != null && !"null".equals(matcher.group(2))) {
                return matcher.group(3);
            }
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

    private static int extractJsonInt(String json, String key, int fallback) {
        String raw = extractJsonString(json, key);
        if (!raw.isBlank()) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
        Pattern numeric = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = numeric.matcher(json == null ? "" : json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private static byte[] decodeHex(String value) {
        if ((value.length() % 2) != 0) {
            throw new IllegalArgumentException("Hex invalido para la llave publica.");
        }
        byte[] decoded = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            decoded[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return decoded;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }

    static String sanitizeRequestedAuxsid(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank()) {
            return "";
        }
        if (!candidate.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("auxsid invalido: " + candidate);
        }
        return candidate;
    }

    static String resolvedOperationalAuxsid(EmissionContextInfo emissionContext) {
        return sanitizeRequestedAuxsid(firstNonBlank(emissionContext.resolvedAuxsid(), emissionContext.auxsid()));
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return "";
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String canonicalBaseUrl(String sourceBaseUrl, String announcedBaseUrl) {
        String fallback = trimTrailingSlash(sourceBaseUrl);
        if (announcedBaseUrl == null || announcedBaseUrl.isBlank()) {
            return fallback;
        }
        try {
            URI source = URI.create(fallback);
            URI announced = URI.create(trimTrailingSlash(announcedBaseUrl));
            if (sameAuthority(source, announced)) {
                return trimTrailingSlash(announced.toString());
            }
            return trimTrailingSlash(rewriteUriAuthority(source, announced.getPath(), announced.getQuery()));
        } catch (Exception ex) {
            return trimTrailingSlash(announcedBaseUrl);
        }
    }

    static String canonicalEndpointUrl(String sourceBaseUrl, String announcedUrl, String defaultPath) {
        String fallback = trimTrailingSlash(sourceBaseUrl) + defaultPath;
        if (announcedUrl == null || announcedUrl.isBlank()) {
            return fallback;
        }
        try {
            URI source = URI.create(trimTrailingSlash(sourceBaseUrl));
            URI announced = URI.create(announcedUrl);
            if (sameAuthority(source, announced)) {
                return announced.toString();
            }
            String path = announced.getPath() == null || announced.getPath().isBlank() ? defaultPath : announced.getPath();
            return rewriteUriAuthority(source, path, announced.getQuery());
        } catch (Exception ex) {
            return announcedUrl;
        }
    }

    private static boolean sameAuthority(URI left, URI right) {
        return firstNonBlank(left.getScheme(), "").equalsIgnoreCase(firstNonBlank(right.getScheme(), ""))
                && firstNonBlank(left.getHost(), "").equalsIgnoreCase(firstNonBlank(right.getHost(), ""))
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String rewriteUriAuthority(URI source, String path, String query) {
        String normalizedPath = (path == null || path.isBlank()) ? "/" : (path.startsWith("/") ? path : "/" + path);
        int port = effectivePort(source);
        boolean includePort = port > 0 && !(("http".equalsIgnoreCase(source.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(source.getScheme()) && port == 443));
        StringBuilder builder = new StringBuilder();
        builder.append(source.getScheme()).append("://").append(source.getHost());
        if (includePort) {
            builder.append(':').append(port);
        }
        builder.append(normalizedPath);
        if (query != null && !query.isBlank()) {
            builder.append('?').append(query);
        }
        return builder.toString();
    }

    record DiscoveryInfo(
            String baseUrl,
            String instanceId,
            String hostname,
            String publicHost,
            String handshakeUrl,
            String publicKeyUrl,
            String ciphertextsUrl,
            String emissionContextUrl,
            int handshakeTtlSeconds,
            String sessionId,
            String sessionName,
            String sessionLabel,
            String electionName,
            String sid,
            boolean acceptingVotes,
            String currentOperation
    ) {
    }

    record EmissionContextInfo(
            boolean ok,
            String sessionId,
            String sessionName,
            String sessionLabel,
            String electionName,
            String sid,
            String requestedAuxsid,
            String resolvedAuxsid,
            String auxsid,
            boolean auxsidChanged,
            boolean accumulated,
            String accumulatedFromAuxsid,
            boolean acceptingVotes
    ) {
        boolean hasContext() {
            return !firstNonBlank(resolvedAuxsid, auxsid).isBlank();
        }

        static EmissionContextInfo empty(DiscoveryInfo discovery, String requestedAuxsid) {
            return new EmissionContextInfo(
                    false,
                    discovery.sessionId(),
                    discovery.sessionName(),
                    discovery.sessionLabel(),
                    discovery.electionName(),
                    discovery.sid(),
                    requestedAuxsid,
                    "",
                    "",
                    false,
                    false,
                    "",
                    discovery.acceptingVotes()
            );
        }
    }

    record HandshakeInfo(
            boolean ok,
            boolean accepted,
            String reason,
            String stationId,
            String requestedAuxsid,
            String leaseId,
            Instant expiresAt,
            String sessionId,
            String sessionName,
            String sessionLabel,
            String electionName
    ) {
    }

    record PublicKeyInfo(
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
    }

    record LeaseContext(
            String baseUrl,
            String instanceId,
            String stationId,
            String leaseId,
            Instant expiresAt,
            String sessionId,
            String sessionName,
            String sessionLabel,
            String electionName,
            String auxsid,
            String requestedAuxsid,
            boolean auxsidChanged,
            boolean accumulated,
            String accumulatedFromAuxsid
    ) {
        static LeaseContext from(DiscoveryInfo discovery, HandshakeInfo handshake, EmissionContextInfo emissionContext) {
            return new LeaseContext(
                    discovery.baseUrl(),
                    discovery.instanceId(),
                    handshake.stationId(),
                    handshake.leaseId(),
                    handshake.expiresAt(),
                    handshake.sessionId(),
                    handshake.sessionName(),
                    handshake.sessionLabel(),
                    handshake.electionName(),
                    resolvedOperationalAuxsid(emissionContext),
                    firstNonBlank(emissionContext.requestedAuxsid(), handshake.requestedAuxsid()),
                    emissionContext.auxsidChanged(),
                    emissionContext.accumulated(),
                    emissionContext.accumulatedFromAuxsid()
            );
        }

        boolean isExpiringSoon() {
            return expiresAt == null || expiresAt.minus(LEASE_RENEW_MARGIN).isBefore(Instant.now());
        }

        boolean isExpired() {
            return expiresAt == null || expiresAt.isBefore(Instant.now());
        }

        LeaseContext withDiscovery(DiscoveryInfo discovery) {
            return new LeaseContext(
                    discovery.baseUrl(),
                    discovery.instanceId(),
                    stationId,
                    leaseId,
                    expiresAt,
                    sessionId,
                    sessionName,
                    sessionLabel,
                    electionName,
                    auxsid,
                    requestedAuxsid,
                    auxsidChanged,
                    accumulated,
                    accumulatedFromAuxsid
            );
        }
    }

    record BootstrapSnapshot(
            boolean serviceMixActive,
            String serviceBaseUrl,
            String resolvedAuxsid,
            String stationId,
            String leaseId,
            String expiresAt,
            String serviceSessionId,
            String serviceSessionName,
            String serviceSessionLabel,
            String serviceElectionName,
            boolean publicKeyOk,
            int publicKeyBytes,
            String serviceInactiveReason,
            boolean serviceBusy,
            String serviceCurrentOperation
    ) {
        static BootstrapSnapshot active(DiscoveryInfo discovery,
                                       LeaseContext lease,
                                       PublicKeyInfo publicKey,
                                       String resolvedAuxsid,
                                       EmissionContextInfo emissionContext) {
            return new BootstrapSnapshot(
                    true,
                    discovery.baseUrl(),
                    resolvedAuxsid,
                    lease == null ? "" : lease.stationId(),
                    lease == null ? "" : lease.leaseId(),
                    lease == null || lease.expiresAt() == null ? "" : lease.expiresAt().toString(),
                    firstNonBlank(publicKey.sessionId(), firstNonBlank(emissionContext.sessionId(), discovery.sessionId())),
                    firstNonBlank(publicKey.sessionName(), firstNonBlank(emissionContext.sessionName(), discovery.sessionName())),
                    firstNonBlank(publicKey.sessionLabel(), firstNonBlank(emissionContext.sessionLabel(), discovery.sessionLabel())),
                    firstNonBlank(publicKey.electionName(), firstNonBlank(emissionContext.electionName(), discovery.electionName())),
                    publicKey.hasKeyMaterial(),
                    publicKey.contentBytes() == null ? 0 : publicKey.contentBytes().length,
                    "",
                    false,
                    ""
            );
        }

        static BootstrapSnapshot inactive(String serviceBaseUrl, String resolvedAuxsid, String reason) {
            return new BootstrapSnapshot(
                    false,
                    firstNonBlank(serviceBaseUrl, ""),
                    firstNonBlank(resolvedAuxsid, ""),
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "Elecciones Generales 2026",
                    false,
                    0,
                    firstNonBlank(reason, "la GUI de mezcladora no responde."),
                    false,
                    ""
            );
        }

        static BootstrapSnapshot busy(DiscoveryInfo discovery,
                                      LeaseContext lease,
                                      String resolvedAuxsid,
                                      EmissionContextInfo emissionContext) {
            String operation = firstNonBlank(discovery.currentOperation(), "operacion_en_curso");
            return new BootstrapSnapshot(
                    false,
                    discovery.baseUrl(),
                    resolvedAuxsid,
                    lease == null ? "" : lease.stationId(),
                    lease == null ? "" : lease.leaseId(),
                    lease == null || lease.expiresAt() == null ? "" : lease.expiresAt().toString(),
                    firstNonBlank(emissionContext.sessionId(), discovery.sessionId()),
                    firstNonBlank(emissionContext.sessionName(), discovery.sessionName()),
                    firstNonBlank(emissionContext.sessionLabel(), discovery.sessionLabel()),
                    firstNonBlank(emissionContext.electionName(), discovery.electionName()),
                    false,
                    0,
                    "La mezcladora esta ocupada con " + operation + ".",
                    true,
                    operation
            );
        }
    }
}
