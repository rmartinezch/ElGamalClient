package pe.gob.onpe.votodigital.votante.android

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class AndroidVoterBridge(
    private val context: Context,
    private val runner: AndroidCipherRunner
) {
    private val runtimeConfig = AndroidRuntimeConfig.load(context)
    private val mixerCoordinator = AndroidMixerLeaseCoordinator(
        runtimeConfig.serviceBaseUrl.ifBlank { DEFAULT_SERVICE_BASE_URL },
        "mesa-047612"
    )

    @JavascriptInterface
    fun callApi(path: String, method: String, body: String?): String {
        return try {
            when (path) {
                "/api/health" -> JSONObject()
                    .put("status", "UP")
                    .put("serverTime", Instant.now().toString())
                    .toString()

                "/api/catalog" -> loadCatalogRaw()
                "/api/bootstrap" -> handleBootstrap().toString()
                "/api/ballot/preview" -> {
                    ensureMethod(method, "POST")
                    buildBundle(parseForm(body)).toJson().toString()
                }
                "/api/ballot/submit" -> {
                    ensureMethod(method, "POST")
                    val form = parseForm(body)
                    val bundle = buildBundle(form)
                    val preferredAuxsid = firstNonBlank(form["auxsid"], "")
                    submitBundle(bundle, preferredAuxsid).toString()
                }
                else -> errorJson(404, "NotFound", "Ruta no soportada: $path").toString()
            }
        } catch (ex: IllegalArgumentException) {
            errorJson(400, ex.javaClass.simpleName, ex.message ?: "Solicitud invalida.").toString()
        } catch (ex: Exception) {
            errorJson(500, ex.javaClass.simpleName, ex.message ?: "Fallo interno.").toString()
        }
    }

    private fun handleBootstrap(): JSONObject {
        val payload = configPayload()
        val snapshot = mixerCoordinator.bootstrap(payload.optString("defaultAuxsid", ""))
        payload.put("serviceBaseUrl", snapshot.serviceBaseUrl)
        payload.put("resolvedAuxsid", snapshot.resolvedAuxsid)
        payload.put("serviceMixActive", snapshot.serviceMixActive)
        payload.put("serviceInactiveReason", snapshot.serviceInactiveReason)
        payload.put("publicKeyOk", snapshot.publicKeyOk)
        payload.put("publicKeyBytes", snapshot.publicKeyBytes)
        payload.put("serviceSessionId", snapshot.serviceSessionId)
        payload.put("serviceSessionName", snapshot.serviceSessionName)
        payload.put("serviceSessionLabel", snapshot.serviceSessionLabel)
        payload.put("serviceElectionName", firstNonBlank(snapshot.serviceElectionName, "Elecciones Generales 2026"))
        payload.put("serviceActiveSession", firstNonBlank(
            snapshot.serviceSessionLabel,
            firstNonBlank(snapshot.serviceSessionName, snapshot.serviceSessionId)
        ))
        payload.put("serviceStateOk", snapshot.serviceMixActive)
        payload.put("serviceOk", snapshot.serviceMixActive)
        payload.put("suggestedAuxsid", snapshot.resolvedAuxsid)
        payload.put("leaseId", snapshot.leaseId)
        payload.put("leaseExpiresAt", snapshot.expiresAt)
        payload.put("stationId", snapshot.stationId)
        payload.put("serviceBusy", snapshot.serviceBusy)
        payload.put("serviceCurrentOperation", snapshot.serviceCurrentOperation)
        payload.put("serviceStateRaw", "")
        payload.put("serviceAuxsidsRaw", "")
        return payload
    }

    private fun submitBundle(bundle: BallotBundle, preferredAuxsid: String): JSONObject {
        val runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) +
            "-" + UUID.randomUUID().toString().substring(0, 8)
        val submissionDir = File(context.filesDir, "voter-submissions/$runId")
        submissionDir.mkdirs()
        val events = mutableListOf<String>()
        events += event("Inicio de emision. runId=$runId")

        val lease = mixerCoordinator.ensureLease(preferredAuxsid)
        val resolvedAuxsid = lease.auxsid
        val serviceBaseUrl = lease.baseUrl
        events += event("Mezcladora seleccionada: $serviceBaseUrl")
        events += event("Handshake activo => station_id=${lease.stationId} lease_id=${lease.leaseId} expires_at=${lease.expiresAt}")
        events += event("Auxsid operativo resuelto: $resolvedAuxsid")
        events += event(
            "Contexto de emision => requested_auxsid=${firstNonBlank(lease.requestedAuxsid, "(vacio)")} " +
                "auxsid_changed=${lease.auxsidChanged} accumulated=${lease.accumulated} " +
                "accumulated_from_auxsid=${firstNonBlank(lease.accumulatedFromAuxsid, "ninguno")}"
        )

        val publicKey = fetchPublicKeyPayload(serviceBaseUrl, "native")
        if (!publicKey.hasKeyMaterial()) {
            throw IllegalStateException("La mezcladora no reporta una sesion activa para recibir votos.")
        }
        val serviceSessionId = firstNonBlank(publicKey.sessionId, lease.sessionId)
        val serviceSessionName = firstNonBlank(publicKey.sessionName, lease.sessionName)
        val serviceSessionLabel = firstNonBlank(publicKey.sessionLabel, lease.sessionLabel)
        events += event("Llave publica nativa descargada. bytes=${publicKey.contentBytes.size}")
        events += event(
            "Sesion remota => id=${firstNonBlank(serviceSessionId, "n/d")} " +
                "name=${firstNonBlank(serviceSessionName, "n/d")} " +
                "label=${firstNonBlank(serviceSessionLabel, "n/d")}"
        )

        val publicKeyWrite = runner.importPublicKeyBytes(publicKey.contentBytes)
        val votesWrite = runner.importVotesText(bundle.bundleText)
        events += event("Cedula canonica validada y serializada en plain_votes.txt")
        events += event("Invocando cifrador desacoplado del ejecutable.")

        val cipherResult = runner.encryptSandboxInputs(CifradorRngMode.SOFTWARE)
        events += event("Proceso de cifrado finalizado. exitCode=${if (cipherResult.success) 0 else 1}")
        if (!cipherResult.success) {
            throw IllegalStateException("No se genero ciphertexts_ext. runId=$runId")
        }

        val ciphertextsText = runner.currentCiphertextsFile().readText(StandardCharsets.UTF_8)
        val ciphertextCount = countNonBlankLines(ciphertextsText)
        events += event("Voto cifrado generado. registros=$ciphertextCount")

        val payload = JSONObject()
            .put("station_id", lease.stationId)
            .put("lease_id", lease.leaseId)
            .put("auxsid", resolvedAuxsid)
            .put("format", "native")
            .put("ciphertexts_ext", ciphertextsText)
            .put("width", VERIFICATUM_WIDTH)
        if (serviceSessionId.isNotBlank()) {
            payload.put("session_id", serviceSessionId)
        }
        if (serviceSessionName.isNotBlank()) {
            payload.put("session_name", serviceSessionName)
        }

        events += event("POST $serviceBaseUrl/api/ciphertexts")
        events += event(
            "curl -X POST \"$serviceBaseUrl/api/ciphertexts\" " +
                "-H \"Content-Type: application/json\" " +
                "-d '{\"station_id\":\"${lease.stationId}\",\"lease_id\":\"${lease.leaseId}\",\"auxsid\":\"$resolvedAuxsid\",\"format\":\"native\"" +
                (if (serviceSessionId.isNotBlank()) ",\"session_id\":\"$serviceSessionId\"" else "") +
                (if (serviceSessionName.isNotBlank()) ",\"session_name\":\"$serviceSessionName\"" else "") +
                ",\"width\":$VERIFICATUM_WIDTH,\"ciphertexts_ext\":\"${summarizeCiphertexts(ciphertextsText)}\"}'"
        )
        events += event(
            "Payload => {\"station_id\":\"${lease.stationId}\",\"lease_id\":\"${lease.leaseId}\",\"auxsid\":\"$resolvedAuxsid\",\"format\":\"native\"" +
                (if (serviceSessionId.isNotBlank()) ",\"session_id\":\"$serviceSessionId\"" else "") +
                (if (serviceSessionName.isNotBlank()) ",\"session_name\":\"$serviceSessionName\"" else "") +
                ",\"width\":$VERIFICATUM_WIDTH,\"ciphertexts_ext_lines\":$ciphertextCount," +
                "\"ciphertexts_ext_bytes\":${ciphertextsText.toByteArray(StandardCharsets.UTF_8).size}," +
                "\"ciphertexts_ext_head\":\"${summarizeCiphertexts(ciphertextsText)}\"}"
        )
        events += event("Remitiendo ciphertexts_ext al servicio remoto con format=native.")

        val receiptRaw = httpPostJson("$serviceBaseUrl/api/ciphertexts", payload.toString())
        val receipt = JSONObject(receiptRaw)
        val receiptAccepted = receipt.optBoolean("ok", false)
        val serviceResolvedAuxsid = firstNonBlank(receipt.optString("resolved_auxsid"), resolvedAuxsid)
        events += event(
            "Respuesta API => validated=${receipt.optBoolean("validated", false)} " +
                "handshake_validated=${receipt.optBoolean("handshake_validated", false)} " +
                "format_resolved=${firstNonBlank(receipt.optString("format_resolved"), "native")} " +
                "party_validated=${firstNonBlank(receipt.optString("party_validated"), "party01")} " +
                "accumulated=${receipt.optBoolean("accumulated", false)} " +
                "session=${firstNonBlank(receipt.optString("session"), "no-informada")}"
        )
        events += event("Servicio remoto respondio ok=$receiptAccepted auxsid=$serviceResolvedAuxsid")

        copyToSubmission(publicKeyWrite.targetFile, File(submissionDir, "publicKey"))
        copyToSubmission(votesWrite.targetFile, File(submissionDir, "plain_votes.txt"))
        copyToSubmission(runner.currentCiphertextsFile(), File(submissionDir, "ciphertexts_ext"))
        copyToSubmission(runner.currentLogFile(), File(submissionDir, "android-cifrador.log"))
        File(submissionDir, "emission-context.json").writeText(
            JSONObject()
                .put("service_base_url", serviceBaseUrl)
                .put("session_id", serviceSessionId)
                .put("session_name", serviceSessionName)
                .put("session_label", serviceSessionLabel)
                .put("requested_auxsid", lease.requestedAuxsid)
                .put("resolved_auxsid", resolvedAuxsid)
                .put("auxsid_changed", lease.auxsidChanged)
                .put("accumulated", lease.accumulated)
                .put("accumulated_from_auxsid", lease.accumulatedFromAuxsid)
                .put("lease_id", lease.leaseId)
                .put("station_id", lease.stationId)
                .toString(2),
            StandardCharsets.UTF_8
        )
        File(submissionDir, "receipt.json").writeText(receipt.toString(2), StandardCharsets.UTF_8)

        return JSONObject()
            .put("runId", runId)
            .put("auxsid", resolvedAuxsid)
            .put("serviceResolvedAuxsid", serviceResolvedAuxsid)
            .put("serviceSessionId", serviceSessionId)
            .put("serviceSessionName", serviceSessionName)
            .put("serviceSessionLabel", serviceSessionLabel)
            .put("serviceAccumulated", receipt.optBoolean("accumulated", lease.accumulated))
            .put("serviceAccumulatedFromAuxsid", firstNonBlank(receipt.optString("accumulated_from_auxsid"), lease.accumulatedFromAuxsid))
            .put("receiptAccepted", receiptAccepted)
            .put("submissionDir", submissionDir.absolutePath)
            .put("receiptRaw", receiptRaw)
            .put("events", JSONArray(events))
            .put("monitorText", events.joinToString("\n"))
            .put("width", VERIFICATUM_WIDTH)
            .put("publicKeyFormat", "native")
            .put("ciphertextsFormat", "native")
            .put("voteSchemaVersion", SCHEMA_VERSION)
    }

    private fun configPayload(): JSONObject {
        return JSONObject()
            .put("serviceBaseUrl", mixerCoordinator.activeBaseUrl().ifBlank { DEFAULT_SERVICE_BASE_URL })
            .put("defaultAuxsid", "")
            .put("electionName", "Elecciones Generales 2026")
            .put("voterProfile", voterProfile())
            .put("stationId", "mesa-047612")
    }

    private fun voterProfile(): JSONObject {
        return JSONObject()
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
    }

    private fun loadCatalogRaw(): String {
        return context.assets.open("votante/catalogo-opciones.json")
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
    }

    private fun parseForm(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        return raw.split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                val parts = pair.split("=", limit = 2)
                val key = decodeFormComponent(parts[0])
                val value = decodeFormComponent(parts.getOrElse(1) { "" })
                key to value
            }
    }

    private fun decodeFormComponent(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun buildBundle(form: Map<String, String>): BallotBundle {
        val districtCode = normalizeRequiredCode("districtCode", form["districtCode"])
        val presidentialParty = normalizeRequiredCode("presidentialParty", form["presidentialParty"])
        val senatorsNationalParty = normalizeRequiredCode("senatorsNationalParty", form["senatorsNationalParty"])
        var senatorsNationalPv1 = normalizeOptionalCode(form["senatorsNationalPv1"])
        var senatorsNationalPv2 = normalizeOptionalCode(form["senatorsNationalPv2"])
        val senatorsRegionalParty = normalizeRequiredCode("senatorsRegionalParty", form["senatorsRegionalParty"])
        val senatorsRegionalPv1 = normalizeOptionalCode(form["senatorsRegionalPv1"])
        val deputiesParty = normalizeRequiredCode("deputiesParty", form["deputiesParty"])
        var deputiesPv1 = normalizeOptionalCode(form["deputiesPv1"])
        var deputiesPv2 = normalizeOptionalCode(form["deputiesPv2"])
        val andeanParty = normalizeRequiredCode("andeanParty", form["andeanParty"])
        var andeanPv1 = normalizeOptionalCode(form["andeanPv1"])
        var andeanPv2 = normalizeOptionalCode(form["andeanPv2"])

        val senatorsNational = normalizeDistinctPreferentials(senatorsNationalPv1, senatorsNationalPv2)
        senatorsNationalPv1 = senatorsNational.first
        senatorsNationalPv2 = senatorsNational.second
        val deputies = normalizeDistinctPreferentials(deputiesPv1, deputiesPv2)
        deputiesPv1 = deputies.first
        deputiesPv2 = deputies.second
        val andean = normalizeDistinctPreferentials(andeanPv1, andeanPv2)
        andeanPv1 = andean.first
        andeanPv2 = andean.second

        val lines = listOf(
            "0100${presidentialParty}0000",
            "0200$senatorsNationalParty$senatorsNationalPv1$senatorsNationalPv2",
            "03$districtCode$senatorsRegionalParty${senatorsRegionalPv1}00",
            "04$districtCode$deputiesParty$deputiesPv1$deputiesPv2",
            "0500$andeanParty$andeanPv1$andeanPv2"
        )
        return BallotBundle(districtCode, lines)
    }

    private fun fetchPublicKeyPayload(baseUrl: String, format: String): PublicKeyPayload {
        val info = mixerCoordinator.fetchPublicKeyInfo(baseUrl, format)
        return PublicKeyPayload(
            contentBytes = info.contentBytes,
            sessionId = info.sessionId,
            sessionName = info.sessionName,
            sessionLabel = info.sessionLabel,
            electionName = info.electionName,
            rawJson = info.rawJson
        )
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        return readResponse(connection, "No se pudo consultar el servicio externo.")
    }

    private fun httpPostJson(url: String, payload: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
        return readResponse(connection, "No se pudo enviar ciphertexts_ext.")
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

    private fun normalizeRequiredCode(field: String, value: String?): String {
        val normalized = normalizeOptionalCode(value)
        if (normalized == "00") {
            throw IllegalArgumentException("Campo obligatorio faltante: $field")
        }
        return normalized
    }

    private fun normalizeOptionalCode(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return "00"
        }
        require(trimmed.matches(Regex("\\d{2}"))) { "Codigo invalido, se esperaban 2 digitos: $trimmed" }
        return trimmed
    }

    private fun normalizeDistinctPreferentials(pv1: String, pv2: String): Pair<String, String> {
        return if (pv1 != "00" && pv1 == pv2) {
            pv1 to "00"
        } else {
            pv1 to pv2
        }
    }

    private fun summarizeCiphertexts(ciphertexts: String): String {
        val compact = ciphertexts.replace("\r", "").replace("\n", "")
        val maxLength = minOf(96, compact.length)
        return compact.substring(0, maxLength) + if (compact.length > maxLength) "..." else ""
    }

    private fun countNonBlankLines(value: String): Int {
        return value.replace("\r", "")
            .split("\n")
            .count { it.isNotBlank() }
    }

    private fun copyToSubmission(source: File, target: File) {
        if (source.isFile) {
            source.copyTo(target, overwrite = true)
        }
    }

    private fun event(message: String): String {
        return "${Instant.now()} | $message"
    }

    private fun ensureMethod(actual: String, expected: String) {
        require(actual.equals(expected, ignoreCase = true)) {
            "Metodo esperado: $expected, recibido: $actual"
        }
    }

    private fun errorJson(status: Int, error: String, message: String): JSONObject {
        return JSONObject()
            .put("status", status)
            .put("error", error)
            .put("message", message)
    }

    private fun firstNonBlank(preferred: String?, fallback: String?): String {
        if (!preferred.isNullOrBlank()) {
            return preferred.trim()
        }
        if (!fallback.isNullOrBlank()) {
            return fallback.trim()
        }
        return ""
    }

    data class BallotBundle(
        val districtCode: String,
        val lines: List<String>
    ) {
        val bundleText: String
            get() = lines.joinToString("\n", postfix = "\n")

        fun toJson(): JSONObject {
            return JSONObject()
                .put("districtCode", districtCode)
                .put("lineCount", lines.size)
                .put("lines", JSONArray(lines))
                .put("bundleText", bundleText)
        }
    }

    data class PublicKeyPayload(
        val contentBytes: ByteArray,
        val sessionId: String,
        val sessionName: String,
        val sessionLabel: String,
        val electionName: String,
        val rawJson: String
    ) {
        fun hasKeyMaterial(): Boolean = contentBytes.isNotEmpty()
        fun hasActiveSession(): Boolean = sessionId.isNotBlank() || sessionName.isNotBlank() || sessionLabel.isNotBlank()
    }

    companion object {
        private const val DEFAULT_SERVICE_BASE_URL = ""
        private const val VERIFICATUM_WIDTH = 1
        private const val SCHEMA_VERSION = "1.0.0-test"
        private const val TIMEOUT_MS = 15000
    }
}
