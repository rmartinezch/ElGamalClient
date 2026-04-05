package pe.gob.onpe.votodigital.votante.ios;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class IOSMixerLeaseCoordinator {

    private static final Logger LOG = Logger.getLogger(IOSMixerLeaseCoordinator.class.getName());
    private static final int DEFAULT_PORT = 7040;
    private static final int DISCOVERY_TIMEOUT_MS = 5000;
    private static final int TIMEOUT_MS = 15000;
    private static final long DISCOVERY_CACHE_TTL_SECONDS = 300;
    private static final long LEASE_RENEW_MARGIN_SECONDS = 15;
    private static final int MAX_SUBNETS_TO_SCAN = 2;

    private final String configuredBaseUrl;
    private final String stationId;
    private volatile LeaseContext currentLease;
    private volatile List<String> cachedResponsiveBaseUrls = List.of();
    private volatile Instant lastSweepAt = Instant.EPOCH;

    public IOSMixerLeaseCoordinator(String configuredBaseUrl, String stationId) {
        this.configuredBaseUrl = trimTrailingSlash(configuredBaseUrl);
        this.stationId = stationId;
    }

    public synchronized BootstrapSnapshot bootstrap(String preferredAuxsid) {
        LOG.info("bootstrap: configuredBaseUrl=" + configuredBaseUrl + " stationId=" + stationId);
        try {
            String requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid);
            String expectedSessionId = currentLease != null ? currentLease.sessionId() : "";
            DiscoveryInfo discovery = findBestDiscovery(expectedSessionId);
            if (discovery == null) {
                LOG.warning("bootstrap: discovery returned null");
                return BootstrapSnapshot.inactive(
                        activeBaseUrl().isEmpty() ? configuredBaseUrl : activeBaseUrl(),
                        "", "No se encontro una mezcladora activa con sesion compatible en la red local.");
            }
            EmissionContextInfo emissionContext = fetchEmissionContext(discovery, requestedAuxsid);
            LOG.info("bootstrap: emissionContext ok=" + emissionContext.hasContext()
                    + " acceptingVotes=" + emissionContext.acceptingVotes());
            if (!emissionContext.hasContext()) {
                return BootstrapSnapshot.inactive(discovery.baseUrl(), "",
                        "La mezcladora no confirmo el contexto de emision por /api/emission-context.");
            }
            String resolvedAuxsid = resolvedOperationalAuxsid(emissionContext);
            LOG.info("bootstrap: resolvedAuxsid=" + resolvedAuxsid);
            LeaseContext reusableLease = null;
            if (currentLease != null
                    && currentLease.baseUrl().equals(discovery.baseUrl())
                    && currentLease.sessionId().equals(discovery.sessionId())
                    && !currentLease.isExpired()) {
                reusableLease = currentLease;
            }
            if (!emissionContext.acceptingVotes()) {
                LOG.info("bootstrap: not accepting votes, returning busy");
                return BootstrapSnapshot.busy(discovery, reusableLease, resolvedAuxsid, emissionContext);
            }
            LOG.info("bootstrap: fetching public key");
            PublicKeyInfo publicKey = fetchPublicKeyInfo(discovery.baseUrl(), "native");
            LOG.info("bootstrap: publicKey bytes=" + publicKey.contentBytes().length);
            return BootstrapSnapshot.active(discovery, reusableLease, publicKey, resolvedAuxsid, emissionContext);
        } catch (Exception ex) {
            LOG.warning("bootstrap: exception: " + ex.getClass().getName() + " - " + ex.getMessage());
            return BootstrapSnapshot.inactive(
                    activeBaseUrl().isEmpty() ? configuredBaseUrl : activeBaseUrl(),
                    "", ex.getMessage() != null ? ex.getMessage()
                    : "No se encontro una mezcladora activa con sesion compatible en la red local.");
        }
    }

    public synchronized LeaseContext ensureLease(String preferredAuxsid) {
        return selectLease(preferredAuxsid, true);
    }

    public String activeBaseUrl() {
        return currentLease != null ? currentLease.baseUrl() : "";
    }

    private LeaseContext selectLease(String preferredAuxsid, boolean forceRenew) {
        String requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid);
        LeaseContext existing = currentLease;
        if (existing != null) {
            DiscoveryInfo currentDiscovery = tryDiscovery(existing.baseUrl(), existing.sessionId());
            if (currentDiscovery != null && currentDiscovery.acceptingVotes()) {
                if (!forceRenew && !existing.isExpiringSoon()) {
                    currentLease = existing.withBaseUrl(currentDiscovery.baseUrl(), currentDiscovery.instanceId());
                    return currentLease;
                }
                HandshakeInfo renewal = tryHandshake(currentDiscovery, requestedAuxsid);
                if (renewal != null && renewal.accepted()) {
                    EmissionContextInfo emCtx = fetchConfirmedEmissionContext(currentDiscovery, renewal.requestedAuxsid());
                    currentLease = LeaseContext.from(currentDiscovery, renewal, emCtx);
                    rememberCandidate(currentDiscovery.baseUrl());
                    return currentLease;
                }
            }
            currentLease = null;
        }
        String expectedSessionId = existing != null ? existing.sessionId() : "";
        for (String candidate : prioritizedCandidates()) {
            DiscoveryInfo disc = tryDiscovery(candidate, expectedSessionId);
            if (disc == null) continue;
            HandshakeInfo hs = tryHandshake(disc, requestedAuxsid);
            if (hs != null && hs.accepted()) {
                EmissionContextInfo emCtx = fetchConfirmedEmissionContext(disc, hs.requestedAuxsid());
                currentLease = LeaseContext.from(disc, hs, emCtx);
                rememberCandidate(disc.baseUrl());
                return currentLease;
            }
        }
        for (DiscoveryInfo disc : sweepLocalNetwork(expectedSessionId)) {
            HandshakeInfo hs = tryHandshake(disc, requestedAuxsid);
            if (hs != null && hs.accepted()) {
                EmissionContextInfo emCtx = fetchConfirmedEmissionContext(disc, hs.requestedAuxsid());
                currentLease = LeaseContext.from(disc, hs, emCtx);
                rememberCandidate(disc.baseUrl());
                return currentLease;
            }
        }
        throw new IllegalStateException("No se encontro una mezcladora activa con sesion compatible en la red local.");
    }

    private DiscoveryInfo findBestDiscovery(String expectedSessionId) {
        DiscoveryInfo busyCandidate = null;
        for (String candidate : prioritizedCandidates()) {
            DiscoveryInfo disc = tryDiscovery(candidate, expectedSessionId);
            if (disc == null) continue;
            if (disc.acceptingVotes()) return disc;
            if (busyCandidate == null) busyCandidate = disc;
        }
        for (DiscoveryInfo disc : sweepLocalNetwork(expectedSessionId)) {
            if (disc.acceptingVotes()) return disc;
            if (busyCandidate == null) busyCandidate = disc;
        }
        return busyCandidate;
    }

    public PublicKeyInfo fetchPublicKeyInfo(String baseUrl, String format) {
        String suffix = format.equalsIgnoreCase("native") ? "" : "?format=" + format;
        String responseBody = httpGet(trimTrailingSlash(baseUrl) + "/api/public-key" + suffix, TIMEOUT_MS);
        String contentHex = jsonStringValue(responseBody, "content");
        byte[] bytes = !contentHex.isBlank() ? decodeHex(contentHex)
                : responseBody.getBytes(StandardCharsets.UTF_8);
        return new PublicKeyInfo(
                bytes,
                jsonStringValue(responseBody, "session_id"),
                jsonStringValue(responseBody, "session_name"),
                jsonStringValue(responseBody, "session_label"),
                jsonStringValue(responseBody, "election_name"),
                responseBody
        );
    }

    private EmissionContextInfo fetchEmissionContext(DiscoveryInfo discovery, String preferredAuxsid) {
        String query = sanitizeRequestedAuxsid(preferredAuxsid);
        String url = discovery.emissionContextUrl() + (query.isBlank() ? "" : "?auxsid=" + query);
        try {
            String body = httpGet(url, DISCOVERY_TIMEOUT_MS);
            return new EmissionContextInfo(
                    jsonBoolValue(body, "ok"),
                    jsonStringOrDefault(body, "session_id", discovery.sessionId()),
                    jsonStringOrDefault(body, "session_name", discovery.sessionName()),
                    jsonStringOrDefault(body, "session_label", discovery.sessionLabel()),
                    jsonStringOrDefault(body, "election_name", discovery.electionName()),
                    jsonStringOrDefault(body, "sid", discovery.sid()),
                    jsonStringOrDefault(body, "requested_auxsid", query),
                    jsonStringValue(body, "resolved_auxsid"),
                    jsonStringValue(body, "auxsid"),
                    jsonBoolValue(body, "auxsid_changed"),
                    jsonBoolValue(body, "accumulated"),
                    jsonStringValue(body, "accumulated_from_auxsid"),
                    jsonBoolValue(body, "accepting_votes")
            );
        } catch (Exception e) {
            return new EmissionContextInfo(false, discovery.sessionId(), discovery.sessionName(),
                    discovery.sessionLabel(), discovery.electionName(), discovery.sid(),
                    query, "", "", false, false, "", discovery.acceptingVotes());
        }
    }

    private EmissionContextInfo fetchConfirmedEmissionContext(DiscoveryInfo discovery, String preferredAuxsid) {
        EmissionContextInfo ctx = fetchEmissionContext(discovery, preferredAuxsid);
        if (!ctx.hasContext()) {
            throw new IllegalStateException("La mezcladora no confirmo el contexto de emision por /api/emission-context.");
        }
        if (resolvedOperationalAuxsid(ctx).isBlank()) {
            throw new IllegalStateException("La mezcladora no devolvio un auxsid operativo valido por /api/emission-context.");
        }
        if (!ctx.acceptingVotes()) {
            throw new IllegalStateException("La mezcladora no esta aceptando votos en este momento.");
        }
        return ctx;
    }

    private List<String> prioritizedCandidates() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (currentLease != null && !currentLease.baseUrl().isBlank()) urls.add(currentLease.baseUrl());
        if (!configuredBaseUrl.isBlank()) urls.add(configuredBaseUrl);
        urls.addAll(cachedResponsiveBaseUrls);
        return new ArrayList<>(urls);
    }

    private List<DiscoveryInfo> sweepLocalNetwork(String expectedSessionId) {
        if (!cachedResponsiveBaseUrls.isEmpty()
                && Duration.between(lastSweepAt, Instant.now()).getSeconds() < DISCOVERY_CACHE_TTL_SECONDS) {
            List<DiscoveryInfo> results = new ArrayList<>();
            for (String url : cachedResponsiveBaseUrls) {
                DiscoveryInfo d = tryDiscovery(url, expectedSessionId);
                if (d != null) results.add(d);
            }
            return results;
        }
        List<String> candidates = subnetCandidates();
        if (candidates.isEmpty()) return List.of();
        ExecutorService executor = Executors.newFixedThreadPool(96);
        try {
            List<Callable<DiscoveryInfo>> tasks = new ArrayList<>();
            for (String c : candidates) {
                tasks.add(() -> tryDiscovery(c, expectedSessionId));
            }
            List<Future<DiscoveryInfo>> futures = executor.invokeAll(tasks);
            List<DiscoveryInfo> discoveries = new ArrayList<>();
            for (Future<DiscoveryInfo> f : futures) {
                try {
                    DiscoveryInfo d = f.get(2, TimeUnit.SECONDS);
                    if (d != null) discoveries.add(d);
                } catch (Exception ignored) {
                }
            }
            discoveries.sort((a, b) -> a.baseUrl().compareTo(b.baseUrl()));
            cachedResponsiveBaseUrls = discoveries.stream().map(DiscoveryInfo::baseUrl).toList();
            lastSweepAt = Instant.now();
            return discoveries;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            executor.shutdownNow();
        }
    }

    private DiscoveryInfo tryDiscovery(String baseUrl, String expectedSessionId) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        try {
            String url = trimTrailingSlash(baseUrl) + "/api/discovery";
            LOG.info("tryDiscovery: GET " + url);
            String body = httpGet(url, DISCOVERY_TIMEOUT_MS);
            LOG.info("tryDiscovery: response length=" + body.length());
            String serverBlock = jsonObjectBlock(body, "server");
            String sessionBlock = jsonObjectBlock(body, "session");
            if (serverBlock.isEmpty() || sessionBlock.isEmpty()) return null;
            if (!jsonBoolValue(body, "ok")) return null;
            if (!jsonBoolValue(serverBlock, "has_active_session")) return null;
            if (!jsonBoolValue(serverBlock, "keygen_ready")) return null;
            String sessionId = jsonStringValue(sessionBlock, "session_id");
            if (!expectedSessionId.isBlank() && !expectedSessionId.equals(sessionId)) return null;
            return new DiscoveryInfo(
                    canonicalBaseUrl(baseUrl, jsonStringValue(serverBlock, "api_url")),
                    jsonStringValue(serverBlock, "instance_id"),
                    jsonStringValue(serverBlock, "hostname"),
                    jsonStringValue(serverBlock, "public_host"),
                    canonicalEndpointUrl(baseUrl, jsonStringValue(serverBlock, "handshake_url"), "/api/handshake"),
                    canonicalEndpointUrl(baseUrl, jsonStringValue(serverBlock, "public_key_url"), "/api/public-key"),
                    canonicalEndpointUrl(baseUrl, jsonStringValue(serverBlock, "ciphertexts_url"), "/api/ciphertexts"),
                    canonicalEndpointUrl(baseUrl, jsonStringValue(serverBlock, "emission_context_url"), "/api/emission-context"),
                    jsonIntValue(serverBlock, "handshake_ttl_seconds", 90),
                    sessionId,
                    jsonStringValue(sessionBlock, "session_name"),
                    jsonStringValue(sessionBlock, "session_label"),
                    jsonStringValue(sessionBlock, "election_name"),
                    jsonStringValue(sessionBlock, "sid"),
                    jsonBoolValue(serverBlock, "accepting_votes"),
                    jsonStringValue(serverBlock, "current_operation")
            );
        } catch (Exception e) {
            LOG.warning("tryDiscovery failed for " + baseUrl + ": " + e.getClass().getName() + " - " + e.getMessage());
            return null;
        }
    }

    private HandshakeInfo tryHandshake(DiscoveryInfo discovery, String preferredAuxsid) {
        try {
            String requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid);
            String payload = "{\"station_id\":\"" + IOSVoterBridge.escapeJsonString(stationId)
                    + "\",\"session_id\":\"" + IOSVoterBridge.escapeJsonString(discovery.sessionId())
                    + "\",\"session_name\":\"" + IOSVoterBridge.escapeJsonString(discovery.sessionName()) + "\""
                    + (requestedAuxsid.isBlank() ? "" : ",\"auxsid\":\"" + IOSVoterBridge.escapeJsonString(requestedAuxsid) + "\"")
                    + "}";
            String body = httpPostJson(discovery.handshakeUrl(), payload, TIMEOUT_MS);
            return new HandshakeInfo(
                    jsonBoolValue(body, "ok"),
                    jsonBoolValue(body, "accepted"),
                    jsonStringValue(body, "reason"),
                    jsonStringOrDefault(body, "station_id", stationId),
                    jsonStringOrDefault(body, "requested_auxsid", requestedAuxsid),
                    jsonStringValue(body, "lease_id"),
                    parseInstant(jsonStringValue(body, "expires_at")),
                    jsonStringOrDefault(body, "session_id", discovery.sessionId()),
                    jsonStringOrDefault(body, "session_name", discovery.sessionName()),
                    jsonStringOrDefault(body, "session_label", discovery.sessionLabel()),
                    jsonStringOrDefault(body, "election_name", discovery.electionName())
            );
        } catch (Exception e) {
            return null;
        }
    }

    private void rememberCandidate(String baseUrl) {
        List<String> updated = new ArrayList<>();
        updated.add(baseUrl);
        for (String u : cachedResponsiveBaseUrls) {
            if (!u.equals(baseUrl)) updated.add(u);
        }
        cachedResponsiveBaseUrls = Collections.unmodifiableList(updated);
        lastSweepAt = Instant.now();
    }

    private List<String> subnetCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        int port = configuredPort();
        var prioritized = new LinkedHashSet<>(prioritizedCandidates());
        LinkedHashSet<String> preferredSubnets = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return List.of();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                if (isLowPriorityInterface(ni)) continue;
                var addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var addr = addresses.nextElement();
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress()) continue;
                    byte[] octets = addr.getAddress();
                    preferredSubnets.add((octets[0] & 0xff) + "." + (octets[1] & 0xff) + "." + (octets[2] & 0xff));
                    if (preferredSubnets.size() >= MAX_SUBNETS_TO_SCAN) break;
                }
                if (preferredSubnets.size() >= MAX_SUBNETS_TO_SCAN) break;
            }
        } catch (Exception e) {
            return List.of();
        }
        for (String subnet : preferredSubnets) {
            for (int host = 1; host <= 254; host++) {
                String candidate = "http://" + subnet + "." + host + ":" + port;
                if (!prioritized.contains(candidate)) candidates.add(candidate);
            }
        }
        return new ArrayList<>(candidates);
    }

    private boolean isLowPriorityInterface(NetworkInterface ni) {
        String desc = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
        return desc.contains("virtual") || desc.contains("vmware") || desc.contains("hyper-v")
                || desc.contains("vethernet") || desc.contains("docker") || desc.contains("wsl")
                || desc.contains("loopback") || desc.contains("bluetooth");
    }

    private int configuredPort() {
        try {
            int port = new URL(configuredBaseUrl).getPort();
            return port > 0 ? port : DEFAULT_PORT;
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    private String httpGet(String url, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            return readResponse(conn);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private String httpPostJson(String url, String payload, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            return readResponse(conn);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = stream != null ? new String(stream.readAllBytes(), StandardCharsets.UTF_8) : "";
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " " + body.substring(0, Math.min(240, body.length())));
        }
        return body;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (Exception e2) {
                return Instant.EPOCH;
            }
        }
    }

    private byte[] decodeHex(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("Hex invalido para la llave publica.");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    String sanitizeRequestedAuxsid(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank()) return "";
        if (!candidate.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("auxsid invalido: " + candidate);
        }
        return candidate;
    }

    String resolvedOperationalAuxsid(EmissionContextInfo ctx) {
        String resolved = ctx.resolvedAuxsid();
        return sanitizeRequestedAuxsid(resolved.isBlank() ? ctx.auxsid() : resolved);
    }

    private String trimTrailingSlash(String value) {
        String current = value.trim();
        while (current.endsWith("/")) current = current.substring(0, current.length() - 1);
        return current;
    }

    String canonicalBaseUrl(String sourceBaseUrl, String announcedBaseUrl) {
        String fallback = trimTrailingSlash(sourceBaseUrl);
        if (announcedBaseUrl.isBlank()) return fallback;
        URL source = parseUrlOrNull(fallback);
        URL announced = parseUrlOrNull(trimTrailingSlash(announcedBaseUrl));
        if (source == null) return trimTrailingSlash(announcedBaseUrl);
        if (announced == null) return trimTrailingSlash(announcedBaseUrl);
        if (sameAuthority(source, announced)) return trimTrailingSlash(announced.toString());
        String path = announced.getPath().isBlank() ? source.getPath() : announced.getPath();
        return trimTrailingSlash(rewriteUrlAuthority(source, path, announced.getQuery()));
    }

    String canonicalEndpointUrl(String sourceBaseUrl, String announcedUrl, String defaultPath) {
        String fallback = trimTrailingSlash(sourceBaseUrl) + defaultPath;
        if (announcedUrl.isBlank()) return fallback;
        URL source = parseUrlOrNull(trimTrailingSlash(sourceBaseUrl));
        URL announced = parseUrlOrNull(announcedUrl);
        if (source == null) return announcedUrl;
        if (announced == null) return announcedUrl;
        if (sameAuthority(source, announced)) return announced.toString();
        String path = announced.getPath().isBlank() ? defaultPath : announced.getPath();
        return rewriteUrlAuthority(source, path, announced.getQuery());
    }

    private URL parseUrlOrNull(String value) {
        try {
            return new URL(value);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private boolean sameAuthority(URL left, URL right) {
        return left.getProtocol().equalsIgnoreCase(right.getProtocol())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
    }

    private String rewriteUrlAuthority(URL source, String path, String query) {
        int port = effectivePort(source);
        String portSuffix = (port > 0 && port != source.getDefaultPort()) ? ":" + port : "";
        String querySuffix = (query == null || query.isBlank()) ? "" : "?" + query;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return source.getProtocol() + "://" + source.getHost() + portSuffix + normalizedPath + querySuffix;
    }

    // --- Minimal JSON helpers (no external library) ---

    private static String jsonStringValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return "";
        int start = json.indexOf('"', colonIdx + 1);
        if (start < 0) return "";
        int end = findClosingQuote(json, start + 1);
        if (end < 0) return "";
        return json.substring(start + 1, end)
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private static String jsonStringOrDefault(String json, String key, String defaultValue) {
        String v = jsonStringValue(json, key);
        return v.isBlank() ? defaultValue : v;
    }

    private static boolean jsonBoolValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return false;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return false;
        String rest = json.substring(colonIdx + 1).stripLeading();
        return rest.startsWith("true");
    }

    private static int jsonIntValue(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return defaultValue;
        String rest = json.substring(colonIdx + 1).stripLeading();
        StringBuilder sb = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c) || c == '-') sb.append(c);
            else break;
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String jsonObjectBlock(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int braceStart = json.indexOf('{', idx + pattern.length());
        if (braceStart < 0) return "";
        int depth = 0;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(braceStart, i + 1);
            }
        }
        return "";
    }

    private static int findClosingQuote(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') return i;
        }
        return -1;
    }

    // --- Records ---

    record DiscoveryInfo(String baseUrl, String instanceId, String hostname, String publicHost,
                         String handshakeUrl, String publicKeyUrl, String ciphertextsUrl,
                         String emissionContextUrl, int handshakeTtlSeconds,
                         String sessionId, String sessionName, String sessionLabel,
                         String electionName, String sid, boolean acceptingVotes, String currentOperation) {
    }

    record HandshakeInfo(boolean ok, boolean accepted, String reason, String stationId,
                         String requestedAuxsid, String leaseId, Instant expiresAt,
                         String sessionId, String sessionName, String sessionLabel, String electionName) {
    }

    record PublicKeyInfo(byte[] contentBytes, String sessionId, String sessionName,
                         String sessionLabel, String electionName, String rawJson) {
        boolean hasKeyMaterial() {
            return contentBytes != null && contentBytes.length > 0;
        }
    }

    record EmissionContextInfo(boolean ok, String sessionId, String sessionName,
                               String sessionLabel, String electionName, String sid,
                               String requestedAuxsid, String resolvedAuxsid, String auxsid,
                               boolean auxsidChanged, boolean accumulated,
                               String accumulatedFromAuxsid, boolean acceptingVotes) {
        boolean hasContext() {
            return (resolvedAuxsid != null && !resolvedAuxsid.isBlank())
                    || (auxsid != null && !auxsid.isBlank());
        }
    }

    record LeaseContext(String baseUrl, String instanceId, String stationId, String leaseId,
                        Instant expiresAt, String sessionId, String sessionName, String sessionLabel,
                        String electionName, String auxsid, String requestedAuxsid,
                        boolean auxsidChanged, boolean accumulated, String accumulatedFromAuxsid) {
        boolean isExpiringSoon() {
            return expiresAt.equals(Instant.EPOCH)
                    || expiresAt.minusSeconds(LEASE_RENEW_MARGIN_SECONDS).isBefore(Instant.now());
        }

        boolean isExpired() {
            return expiresAt.equals(Instant.EPOCH) || expiresAt.isBefore(Instant.now());
        }

        LeaseContext withBaseUrl(String newBaseUrl, String newInstanceId) {
            return new LeaseContext(newBaseUrl, newInstanceId, stationId, leaseId, expiresAt,
                    sessionId, sessionName, sessionLabel, electionName, auxsid,
                    requestedAuxsid, auxsidChanged, accumulated, accumulatedFromAuxsid);
        }

        static LeaseContext from(DiscoveryInfo disc, HandshakeInfo hs, EmissionContextInfo ctx) {
            String auxsid = ctx.resolvedAuxsid().isBlank() ? ctx.auxsid() : ctx.resolvedAuxsid();
            String reqAuxsid = ctx.requestedAuxsid().isBlank() ? hs.requestedAuxsid() : ctx.requestedAuxsid();
            return new LeaseContext(disc.baseUrl(), disc.instanceId(), hs.stationId(), hs.leaseId(),
                    hs.expiresAt(), hs.sessionId(), hs.sessionName(), hs.sessionLabel(),
                    hs.electionName(), auxsid, reqAuxsid, ctx.auxsidChanged(),
                    ctx.accumulated(), ctx.accumulatedFromAuxsid());
        }
    }

    record BootstrapSnapshot(boolean serviceMixActive, String serviceBaseUrl, String resolvedAuxsid,
                             String stationId, String leaseId, String expiresAt,
                             String serviceSessionId, String serviceSessionName,
                             String serviceSessionLabel, String serviceElectionName,
                             boolean publicKeyOk, int publicKeyBytes,
                             String serviceInactiveReason,
                             boolean serviceBusy, String serviceCurrentOperation) {
        static BootstrapSnapshot active(DiscoveryInfo disc, LeaseContext lease, PublicKeyInfo pk,
                                        String resolvedAuxsid, EmissionContextInfo ctx) {
            String sid = firstNonBlank(pk.sessionId(), ctx.sessionId(), disc.sessionId());
            String sName = firstNonBlank(pk.sessionName(), ctx.sessionName(), disc.sessionName());
            String sLabel = firstNonBlank(pk.sessionLabel(), ctx.sessionLabel(), disc.sessionLabel());
            String eName = firstNonBlank(pk.electionName(), ctx.electionName(), disc.electionName());
            return new BootstrapSnapshot(true, disc.baseUrl(), resolvedAuxsid,
                    lease != null ? lease.stationId() : "",
                    lease != null ? lease.leaseId() : "",
                    lease != null ? lease.expiresAt().toString() : "",
                    sid, sName, sLabel, eName,
                    pk.hasKeyMaterial(), pk.contentBytes().length, "",
                    false, "");
        }

        static BootstrapSnapshot inactive(String baseUrl, String resolvedAuxsid, String reason) {
            return new BootstrapSnapshot(false, baseUrl, resolvedAuxsid,
                    "", "", "", "", "", "", "Elecciones Generales 2026",
                    false, 0, reason, false, "");
        }

        static BootstrapSnapshot busy(DiscoveryInfo disc, LeaseContext lease,
                                      String resolvedAuxsid, EmissionContextInfo ctx) {
            String operation = disc.currentOperation().isBlank() ? "operacion_en_curso" : disc.currentOperation();
            String sid = firstNonBlank(ctx.sessionId(), disc.sessionId());
            String sName = firstNonBlank(ctx.sessionName(), disc.sessionName());
            String sLabel = firstNonBlank(ctx.sessionLabel(), disc.sessionLabel());
            String eName = firstNonBlank(ctx.electionName(), disc.electionName());
            return new BootstrapSnapshot(false, disc.baseUrl(), resolvedAuxsid,
                    lease != null ? lease.stationId() : "",
                    lease != null ? lease.leaseId() : "",
                    lease != null ? lease.expiresAt().toString() : "",
                    sid, sName, sLabel, eName,
                    false, 0,
                    "La mezcladora esta ocupada con " + operation + ".",
                    true, operation);
        }

        private static String firstNonBlank(String... values) {
            for (String v : values) {
                if (v != null && !v.isBlank()) return v.trim();
            }
            return "";
        }
    }
}
