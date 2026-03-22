package pe.gob.onpe.votodigital.votante.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.MalformedURLException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidMixerLeaseCoordinator(
    configuredBaseUrl: String,
    private val stationId: String
) {

    private val configuredBaseUrl = trimTrailingSlash(configuredBaseUrl)
    @Volatile private var currentLease: LeaseContext? = null
    @Volatile private var cachedResponsiveBaseUrls: List<String> = emptyList()
    @Volatile private var lastSweepAt: Instant = Instant.EPOCH

    @Synchronized
    fun bootstrap(preferredAuxsid: String): BootstrapSnapshot {
        return try {
            val requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid)
            val expectedSessionId = currentLease?.sessionId.orEmpty()
            val discovery = findBestDiscovery(expectedSessionId)
                ?: return BootstrapSnapshot.inactive(
                    activeBaseUrl().ifBlank { configuredBaseUrl },
                    "",
                    "No se encontro una mezcladora activa con sesion compatible en la red local."
                )
            val emissionContext = fetchEmissionContext(discovery, requestedAuxsid)
            if (!emissionContext.hasContext()) {
                return BootstrapSnapshot.inactive(
                    discovery.baseUrl,
                    "",
                    "La mezcladora no confirmo el contexto de emision por /api/emission-context."
                )
            }
            val resolvedAuxsid = resolvedOperationalAuxsid(emissionContext)
            val reusableLease = currentLease?.takeIf {
                it.baseUrl == discovery.baseUrl && it.sessionId == discovery.sessionId && !it.isExpired()
            }
            val acceptingVotes = emissionContext.acceptingVotes
            if (!acceptingVotes) {
                BootstrapSnapshot.busy(discovery, reusableLease, resolvedAuxsid, emissionContext)
            } else {
                val publicKey = fetchPublicKeyInfo(discovery.baseUrl, "native")
                BootstrapSnapshot.active(discovery, reusableLease, publicKey, resolvedAuxsid, emissionContext)
            }
        } catch (ex: Exception) {
            BootstrapSnapshot.inactive(
                activeBaseUrl().ifBlank { configuredBaseUrl },
                "",
                ex.message ?: "No se encontro una mezcladora activa con sesion compatible en la red local."
            )
        }
    }

    @Synchronized
    fun ensureLease(preferredAuxsid: String): LeaseContext {
        return selectLease(preferredAuxsid, forceRenew = true)
    }

    fun activeBaseUrl(): String = currentLease?.baseUrl.orEmpty()

    private fun selectLease(preferredAuxsid: String, forceRenew: Boolean): LeaseContext {
        val requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid)
        val existing = currentLease
        if (existing != null) {
            val currentDiscovery = tryDiscovery(existing.baseUrl, existing.sessionId)
            if (currentDiscovery != null && currentDiscovery.acceptingVotes) {
                if (!forceRenew && !existing.isExpiringSoon()) {
                    currentLease = existing.copy(
                        baseUrl = currentDiscovery.baseUrl,
                        instanceId = currentDiscovery.instanceId
                    )
                    return currentLease!!
                }
                val renewal = tryHandshake(currentDiscovery, requestedAuxsid)
                if (renewal != null && renewal.accepted) {
                    val emissionContext = fetchConfirmedEmissionContext(currentDiscovery, renewal.requestedAuxsid)
                    currentLease = LeaseContext.from(currentDiscovery, renewal, emissionContext)
                    rememberCandidate(currentDiscovery.baseUrl)
                    return currentLease!!
                }
            }
            currentLease = null
        }

        val expectedSessionId = existing?.sessionId.orEmpty()
        for (candidate in prioritizedCandidates()) {
            val discovery = tryDiscovery(candidate, expectedSessionId) ?: continue
            val handshake = tryHandshake(discovery, requestedAuxsid) ?: continue
            if (handshake.accepted) {
                val emissionContext = fetchConfirmedEmissionContext(discovery, handshake.requestedAuxsid)
                currentLease = LeaseContext.from(discovery, handshake, emissionContext)
                rememberCandidate(discovery.baseUrl)
                return currentLease!!
            }
        }

        for (discovery in sweepLocalNetwork(expectedSessionId)) {
            val handshake = tryHandshake(discovery, requestedAuxsid) ?: continue
            if (handshake.accepted) {
                val emissionContext = fetchConfirmedEmissionContext(discovery, handshake.requestedAuxsid)
                currentLease = LeaseContext.from(discovery, handshake, emissionContext)
                rememberCandidate(discovery.baseUrl)
                return currentLease!!
            }
        }

        throw IllegalStateException("No se encontro una mezcladora activa con sesion compatible en la red local.")
    }

    private fun findBestDiscovery(expectedSessionId: String): DiscoveryInfo? {
        var busyCandidate: DiscoveryInfo? = null
        for (candidate in prioritizedCandidates()) {
            val discovery = tryDiscovery(candidate, expectedSessionId) ?: continue
            if (discovery.acceptingVotes) {
                return discovery
            }
            if (busyCandidate == null) {
                busyCandidate = discovery
            }
        }
        for (discovery in sweepLocalNetwork(expectedSessionId)) {
            if (discovery.acceptingVotes) {
                return discovery
            }
            if (busyCandidate == null) {
                busyCandidate = discovery
            }
        }
        return busyCandidate
    }

    fun fetchPublicKeyInfo(baseUrl: String, format: String): PublicKeyInfo {
        val suffix = if (format.equals("native", ignoreCase = true)) "" else "?format=$format"
        val response = JSONObject(httpGet("${trimTrailingSlash(baseUrl)}/api/public-key$suffix", TIMEOUT_MS))
        val contentHex = response.optString("content", "")
        val bytes = if (contentHex.isNotBlank()) decodeHex(contentHex) else response.toString().toByteArray(StandardCharsets.UTF_8)
        return PublicKeyInfo(
            contentBytes = bytes,
            sessionId = response.optString("session_id", ""),
            sessionName = response.optString("session_name", ""),
            sessionLabel = response.optString("session_label", ""),
            electionName = response.optString("election_name", ""),
            rawJson = response.toString()
        )
    }

    private fun fetchEmissionContext(discovery: DiscoveryInfo, preferredAuxsid: String): EmissionContextInfo {
        val query = sanitizeRequestedAuxsid(preferredAuxsid)
        val url = buildString {
            append(discovery.emissionContextUrl)
            if (query.isNotBlank()) {
                append("?auxsid=")
                append(query)
            }
        }
        return try {
            val response = JSONObject(httpGet(url, DISCOVERY_TIMEOUT_MS))
            EmissionContextInfo(
                ok = response.optBoolean("ok", false),
                sessionId = response.optString("session_id", discovery.sessionId),
                sessionName = response.optString("session_name", discovery.sessionName),
                sessionLabel = response.optString("session_label", discovery.sessionLabel),
                electionName = response.optString("election_name", discovery.electionName),
                sid = response.optString("sid", discovery.sid),
                requestedAuxsid = response.optString("requested_auxsid", query),
                resolvedAuxsid = response.optString("resolved_auxsid", ""),
                auxsid = response.optString("auxsid", ""),
                auxsidChanged = response.optBoolean("auxsid_changed", false),
                accumulated = response.optBoolean("accumulated", false),
                accumulatedFromAuxsid = response.optString("accumulated_from_auxsid", ""),
                acceptingVotes = response.optBoolean("accepting_votes", discovery.acceptingVotes)
            )
        } catch (_: Exception) {
            EmissionContextInfo(
                ok = false,
                sessionId = discovery.sessionId,
                sessionName = discovery.sessionName,
                sessionLabel = discovery.sessionLabel,
                electionName = discovery.electionName,
                sid = discovery.sid,
                requestedAuxsid = query,
                resolvedAuxsid = "",
                auxsid = "",
                auxsidChanged = false,
                accumulated = false,
                accumulatedFromAuxsid = "",
                acceptingVotes = discovery.acceptingVotes
            )
        }
    }

    private fun fetchConfirmedEmissionContext(discovery: DiscoveryInfo, preferredAuxsid: String): EmissionContextInfo {
        val emissionContext = fetchEmissionContext(discovery, preferredAuxsid)
        if (!emissionContext.hasContext()) {
            throw IllegalStateException("La mezcladora no confirmo el contexto de emision por /api/emission-context.")
        }
        if (resolvedOperationalAuxsid(emissionContext).isBlank()) {
            throw IllegalStateException("La mezcladora no devolvio un auxsid operativo valido por /api/emission-context.")
        }
        if (!emissionContext.acceptingVotes) {
            throw IllegalStateException("La mezcladora no esta aceptando votos en este momento.")
        }
        return emissionContext
    }

    private fun prioritizedCandidates(): List<String> {
        val urls = LinkedHashSet<String>()
        currentLease?.baseUrl?.takeIf { it.isNotBlank() }?.let(urls::add)
        configuredBaseUrl.takeIf { it.isNotBlank() }?.let(urls::add)
        urls.addAll(cachedResponsiveBaseUrls)
        return urls.toList()
    }

    private fun sweepLocalNetwork(expectedSessionId: String): List<DiscoveryInfo> {
        if (cachedResponsiveBaseUrls.isNotEmpty()
            && java.time.Duration.between(lastSweepAt, Instant.now()).seconds < DISCOVERY_CACHE_TTL_SECONDS) {
            return cachedResponsiveBaseUrls.mapNotNull { tryDiscovery(it, expectedSessionId) }
        }

        val candidates = subnetCandidates()
        if (candidates.isEmpty()) {
            return emptyList()
        }
        val executor = Executors.newFixedThreadPool(96)
        return try {
            val futures = executor.invokeAll(candidates.map { candidate ->
                Callable { tryDiscovery(candidate, expectedSessionId) }
            })
            val discoveries = futures.mapNotNull {
                try {
                    it.get(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it.baseUrl }
            cachedResponsiveBaseUrls = discoveries.map { it.baseUrl }
            lastSweepAt = Instant.now()
            discoveries
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun tryDiscovery(baseUrl: String, expectedSessionId: String): DiscoveryInfo? {
        if (baseUrl.isBlank()) {
            return null
        }
        return try {
            val response = JSONObject(httpGet("${trimTrailingSlash(baseUrl)}/api/discovery", DISCOVERY_TIMEOUT_MS))
            val server = response.optJSONObject("server") ?: return null
            val session = response.optJSONObject("session") ?: return null
            val sessionId = session.optString("session_id", "")
            if (!response.optBoolean("ok", false)
                || !server.optBoolean("has_active_session", false)
                || !server.optBoolean("keygen_ready", false)) {
                return null
            }
            if (expectedSessionId.isNotBlank() && expectedSessionId != sessionId) {
                return null
            }
            DiscoveryInfo(
                baseUrl = canonicalBaseUrl(baseUrl, server.optString("api_url", "")),
                instanceId = server.optString("instance_id", ""),
                hostname = server.optString("hostname", ""),
                publicHost = server.optString("public_host", ""),
                handshakeUrl = canonicalEndpointUrl(baseUrl, server.optString("handshake_url", ""), "/api/handshake"),
                publicKeyUrl = canonicalEndpointUrl(baseUrl, server.optString("public_key_url", ""), "/api/public-key"),
                ciphertextsUrl = canonicalEndpointUrl(baseUrl, server.optString("ciphertexts_url", ""), "/api/ciphertexts"),
                emissionContextUrl = canonicalEndpointUrl(baseUrl, server.optString("emission_context_url", ""), "/api/emission-context"),
                handshakeTtlSeconds = server.optInt("handshake_ttl_seconds", 90),
                sessionId = sessionId,
                sessionName = session.optString("session_name", ""),
                sessionLabel = session.optString("session_label", ""),
                electionName = session.optString("election_name", ""),
                sid = session.optString("sid", ""),
                acceptingVotes = server.optBoolean("accepting_votes", false),
                currentOperation = server.optString("current_operation", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun tryHandshake(discovery: DiscoveryInfo, preferredAuxsid: String): HandshakeInfo? {
        return try {
            val requestedAuxsid = sanitizeRequestedAuxsid(preferredAuxsid)
            val payload = JSONObject()
                .put("station_id", stationId)
                .put("session_id", discovery.sessionId)
                .put("session_name", discovery.sessionName)
            if (requestedAuxsid.isNotBlank()) {
                payload.put("auxsid", requestedAuxsid)
            }
            val response = JSONObject(httpPostJson(discovery.handshakeUrl, payload.toString(), TIMEOUT_MS))
            HandshakeInfo(
                ok = response.optBoolean("ok", false),
                accepted = response.optBoolean("accepted", false),
                reason = response.optString("reason", ""),
                stationId = response.optString("station_id", stationId),
                requestedAuxsid = response.optString("requested_auxsid", requestedAuxsid),
                leaseId = response.optString("lease_id", ""),
                expiresAt = parseInstant(response.optString("expires_at", "")),
                sessionId = response.optString("session_id", discovery.sessionId),
                sessionName = response.optString("session_name", discovery.sessionName),
                sessionLabel = response.optString("session_label", discovery.sessionLabel),
                electionName = response.optString("election_name", discovery.electionName)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun rememberCandidate(baseUrl: String) {
        val updated = ArrayList<String>()
        updated.add(baseUrl)
        updated.addAll(cachedResponsiveBaseUrls.filterNot { it == baseUrl })
        cachedResponsiveBaseUrls = Collections.unmodifiableList(updated)
        lastSweepAt = Instant.now()
    }

    private fun subnetCandidates(): List<String> {
        val candidates = LinkedHashSet<String>()
        val port = configuredPort()
        val prioritized = prioritizedCandidates().toSet()
        val preferredSubnets = LinkedHashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
                    continue
                }
                if (isLowPriorityInterface(networkInterface)) {
                    continue
                }
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address || !address.isSiteLocalAddress) {
                        continue
                    }
                    val octets = address.address
                    preferredSubnets.add("${octets[0].toInt() and 0xff}.${octets[1].toInt() and 0xff}.${octets[2].toInt() and 0xff}")
                    if (preferredSubnets.size >= MAX_SUBNETS_TO_SCAN) {
                        break
                    }
                }
                if (preferredSubnets.size >= MAX_SUBNETS_TO_SCAN) {
                    break
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        for (subnet in preferredSubnets) {
            for (host in 1..254) {
                val candidate = "http://$subnet.$host:$port"
                if (!prioritized.contains(candidate)) {
                    candidates.add(candidate)
                }
            }
        }
        return candidates.toList()
    }

    private fun isLowPriorityInterface(networkInterface: NetworkInterface): Boolean {
        val descriptor = "${networkInterface.displayName} ${networkInterface.name}".lowercase()
        return descriptor.contains("virtual")
            || descriptor.contains("vmware")
            || descriptor.contains("hyper-v")
            || descriptor.contains("vethernet")
            || descriptor.contains("docker")
            || descriptor.contains("wsl")
            || descriptor.contains("loopback")
            || descriptor.contains("bluetooth")
    }

    private fun configuredPort(): Int {
        return try {
            val port = URL(configuredBaseUrl).port
            if (port > 0) port else DEFAULT_PORT
        } catch (_: Exception) {
            DEFAULT_PORT
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        return readResponse(connection, "No se pudo consultar el servicio externo.")
    }

    private fun httpPostJson(url: String, payload: String, timeoutMs: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
        return readResponse(connection, "No se pudo completar el handshake.")
    }

    private fun readResponse(connection: HttpURLConnection, message: String): String {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
        if (status !in 200..299) {
            throw IllegalStateException("$message HTTP $status ${body.take(240)}".trim())
        }
        return body
    }

    private fun parseInstant(value: String): Instant {
        if (value.isBlank()) {
            return Instant.EPOCH
        }
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            OffsetDateTime.parse(value).toInstant()
        }
    }

    private fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex invalido para la llave publica." }
        return ByteArray(value.length / 2) { index ->
            val offset = index * 2
            value.substring(offset, offset + 2).toInt(16).toByte()
        }
    }

    internal fun sanitizeRequestedAuxsid(value: String?): String {
        val candidate = value?.trim().orEmpty()
        if (candidate.isBlank()) {
            return ""
        }
        require(candidate.matches(Regex("[A-Za-z0-9._-]+"))) { "auxsid invalido: $candidate" }
        return candidate
    }

    internal fun resolvedOperationalAuxsid(emissionContext: EmissionContextInfo): String {
        return sanitizeRequestedAuxsid(
            emissionContext.resolvedAuxsid.ifBlank { emissionContext.auxsid }
        )
    }

    private fun trimTrailingSlash(value: String): String {
        var current = value.trim()
        while (current.endsWith("/")) {
            current = current.dropLast(1)
        }
        return current
    }

    internal fun canonicalBaseUrl(sourceBaseUrl: String, announcedBaseUrl: String): String {
        val fallback = trimTrailingSlash(sourceBaseUrl)
        if (announcedBaseUrl.isBlank()) {
            return fallback
        }
        val source = parseUrlOrNull(fallback) ?: return trimTrailingSlash(announcedBaseUrl)
        val announced = parseUrlOrNull(trimTrailingSlash(announcedBaseUrl)) ?: return trimTrailingSlash(announcedBaseUrl)
        if (sameAuthority(source, announced)) {
            return trimTrailingSlash(announced.toString())
        }
        return trimTrailingSlash(rewriteUrlAuthority(source, announced.path.ifBlank { source.path }, announced.query))
    }

    internal fun canonicalEndpointUrl(sourceBaseUrl: String, announcedUrl: String, defaultPath: String): String {
        val fallback = "${trimTrailingSlash(sourceBaseUrl)}$defaultPath"
        if (announcedUrl.isBlank()) {
            return fallback
        }
        val source = parseUrlOrNull(trimTrailingSlash(sourceBaseUrl)) ?: return announcedUrl
        val announced = parseUrlOrNull(announcedUrl) ?: return announcedUrl
        if (sameAuthority(source, announced)) {
            return announced.toString()
        }
        return rewriteUrlAuthority(source, announced.path.ifBlank { defaultPath }, announced.query)
    }

    private fun parseUrlOrNull(value: String): URL? {
        return try {
            URL(value)
        } catch (_: MalformedURLException) {
            null
        }
    }

    private fun sameAuthority(left: URL, right: URL): Boolean {
        return left.protocol.equals(right.protocol, ignoreCase = true)
            && left.host.equals(right.host, ignoreCase = true)
            && effectivePort(left) == effectivePort(right)
    }

    private fun effectivePort(url: URL): Int {
        return if (url.port >= 0) url.port else url.defaultPort
    }

    private fun rewriteUrlAuthority(source: URL, path: String, query: String?): String {
        val port = effectivePort(source)
        val portSuffix = if (port > 0 && port != source.defaultPort) ":$port" else ""
        val querySuffix = if (query.isNullOrBlank()) "" else "?$query"
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "${source.protocol}://${source.host}$portSuffix$normalizedPath$querySuffix"
    }

    data class DiscoveryInfo(
        val baseUrl: String,
        val instanceId: String,
        val hostname: String,
        val publicHost: String,
        val handshakeUrl: String,
        val publicKeyUrl: String,
        val ciphertextsUrl: String,
        val emissionContextUrl: String,
        val handshakeTtlSeconds: Int,
        val sessionId: String,
        val sessionName: String,
        val sessionLabel: String,
        val electionName: String,
        val sid: String,
        val acceptingVotes: Boolean,
        val currentOperation: String
    )

    data class HandshakeInfo(
        val ok: Boolean,
        val accepted: Boolean,
        val reason: String,
        val stationId: String,
        val requestedAuxsid: String,
        val leaseId: String,
        val expiresAt: Instant,
        val sessionId: String,
        val sessionName: String,
        val sessionLabel: String,
        val electionName: String
    )

    data class PublicKeyInfo(
        val contentBytes: ByteArray,
        val sessionId: String,
        val sessionName: String,
        val sessionLabel: String,
        val electionName: String,
        val rawJson: String
    ) {
        fun hasKeyMaterial(): Boolean = contentBytes.isNotEmpty()
    }

    data class EmissionContextInfo(
        val ok: Boolean,
        val sessionId: String,
        val sessionName: String,
        val sessionLabel: String,
        val electionName: String,
        val sid: String,
        val requestedAuxsid: String,
        val resolvedAuxsid: String,
        val auxsid: String,
        val auxsidChanged: Boolean,
        val accumulated: Boolean,
        val accumulatedFromAuxsid: String,
        val acceptingVotes: Boolean
    ) {
        fun hasContext(): Boolean = resolvedAuxsid.isNotBlank() || auxsid.isNotBlank()
    }

    data class LeaseContext(
        val baseUrl: String,
        val instanceId: String,
        val stationId: String,
        val leaseId: String,
        val expiresAt: Instant,
        val sessionId: String,
        val sessionName: String,
        val sessionLabel: String,
        val electionName: String,
        val auxsid: String,
        val requestedAuxsid: String,
        val auxsidChanged: Boolean,
        val accumulated: Boolean,
        val accumulatedFromAuxsid: String
    ) {
        fun isExpiringSoon(): Boolean = expiresAt == Instant.EPOCH || expiresAt.minusSeconds(LEASE_RENEW_MARGIN_SECONDS).isBefore(Instant.now())
        fun isExpired(): Boolean = expiresAt == Instant.EPOCH || expiresAt.isBefore(Instant.now())

        companion object {
            fun from(
                discovery: DiscoveryInfo,
                handshake: HandshakeInfo,
                emissionContext: EmissionContextInfo
            ): LeaseContext {
                return LeaseContext(
                    baseUrl = discovery.baseUrl,
                    instanceId = discovery.instanceId,
                    stationId = handshake.stationId,
                    leaseId = handshake.leaseId,
                    expiresAt = handshake.expiresAt,
                    sessionId = handshake.sessionId,
                    sessionName = handshake.sessionName,
                    sessionLabel = handshake.sessionLabel,
                    electionName = handshake.electionName,
                    auxsid = emissionContext.resolvedAuxsid.ifBlank { emissionContext.auxsid },
                    requestedAuxsid = emissionContext.requestedAuxsid.ifBlank { handshake.requestedAuxsid },
                    auxsidChanged = emissionContext.auxsidChanged,
                    accumulated = emissionContext.accumulated,
                    accumulatedFromAuxsid = emissionContext.accumulatedFromAuxsid
                )
            }
        }
    }

    data class BootstrapSnapshot(
        val serviceMixActive: Boolean,
        val serviceBaseUrl: String,
        val resolvedAuxsid: String,
        val stationId: String,
        val leaseId: String,
        val expiresAt: String,
        val serviceSessionId: String,
        val serviceSessionName: String,
        val serviceSessionLabel: String,
        val serviceElectionName: String,
        val publicKeyOk: Boolean,
        val publicKeyBytes: Int,
        val serviceInactiveReason: String,
        val serviceBusy: Boolean,
        val serviceCurrentOperation: String
    ) {
        companion object {
            fun active(
                discovery: DiscoveryInfo,
                lease: LeaseContext?,
                publicKey: PublicKeyInfo,
                resolvedAuxsid: String,
                emissionContext: EmissionContextInfo
            ): BootstrapSnapshot {
                return BootstrapSnapshot(
                    serviceMixActive = true,
                    serviceBaseUrl = discovery.baseUrl,
                    resolvedAuxsid = resolvedAuxsid,
                    stationId = lease?.stationId.orEmpty(),
                    leaseId = lease?.leaseId.orEmpty(),
                    expiresAt = lease?.expiresAt?.toString().orEmpty(),
                    serviceSessionId = publicKey.sessionId.ifBlank { emissionContext.sessionId.ifBlank { discovery.sessionId } },
                    serviceSessionName = publicKey.sessionName.ifBlank { emissionContext.sessionName.ifBlank { discovery.sessionName } },
                    serviceSessionLabel = publicKey.sessionLabel.ifBlank { emissionContext.sessionLabel.ifBlank { discovery.sessionLabel } },
                    serviceElectionName = publicKey.electionName.ifBlank { emissionContext.electionName.ifBlank { discovery.electionName } },
                    publicKeyOk = publicKey.hasKeyMaterial(),
                    publicKeyBytes = publicKey.contentBytes.size,
                    serviceInactiveReason = "",
                    serviceBusy = false,
                    serviceCurrentOperation = ""
                )
            }

            fun inactive(serviceBaseUrl: String, resolvedAuxsid: String, reason: String): BootstrapSnapshot {
                return BootstrapSnapshot(
                    serviceMixActive = false,
                    serviceBaseUrl = serviceBaseUrl,
                    resolvedAuxsid = resolvedAuxsid,
                    stationId = "",
                    leaseId = "",
                    expiresAt = "",
                    serviceSessionId = "",
                    serviceSessionName = "",
                    serviceSessionLabel = "",
                    serviceElectionName = "Elecciones Generales 2026",
                    publicKeyOk = false,
                    publicKeyBytes = 0,
                    serviceInactiveReason = reason,
                    serviceBusy = false,
                    serviceCurrentOperation = ""
                )
            }

            fun busy(
                discovery: DiscoveryInfo,
                lease: LeaseContext?,
                resolvedAuxsid: String,
                emissionContext: EmissionContextInfo
            ): BootstrapSnapshot {
                val operation = discovery.currentOperation.ifBlank { "operacion_en_curso" }
                return BootstrapSnapshot(
                    serviceMixActive = false,
                    serviceBaseUrl = discovery.baseUrl,
                    resolvedAuxsid = resolvedAuxsid,
                    stationId = lease?.stationId.orEmpty(),
                    leaseId = lease?.leaseId.orEmpty(),
                    expiresAt = lease?.expiresAt?.toString().orEmpty(),
                    serviceSessionId = emissionContext.sessionId.ifBlank { discovery.sessionId },
                    serviceSessionName = emissionContext.sessionName.ifBlank { discovery.sessionName },
                    serviceSessionLabel = emissionContext.sessionLabel.ifBlank { discovery.sessionLabel },
                    serviceElectionName = emissionContext.electionName.ifBlank { discovery.electionName },
                    publicKeyOk = false,
                    publicKeyBytes = 0,
                    serviceInactiveReason = "La mezcladora esta ocupada con $operation.",
                    serviceBusy = true,
                    serviceCurrentOperation = operation
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_PORT = 7040
        private const val DISCOVERY_TIMEOUT_MS = 250
        private const val TIMEOUT_MS = 15000
        private const val DISCOVERY_CACHE_TTL_SECONDS = 300L
        private const val LEASE_RENEW_MARGIN_SECONDS = 15L
        private const val MAX_SUBNETS_TO_SCAN = 2
    }
}
