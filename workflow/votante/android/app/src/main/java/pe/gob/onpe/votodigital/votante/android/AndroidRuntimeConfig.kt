package pe.gob.onpe.votodigital.votante.android

import android.content.Context
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class AndroidRuntimeConfig(
    val serviceBaseUrl: String
) {
    companion object {
        private const val ASSET_PATH = "votante/runtime-config.json"

        fun load(context: Context): AndroidRuntimeConfig {
            val assetJson = try {
                context.assets.open(ASSET_PATH)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            if (assetJson.isBlank()) {
                return AndroidRuntimeConfig(serviceBaseUrl = "")
            }
            return try {
                val payload = JSONObject(assetJson)
                AndroidRuntimeConfig(
                    serviceBaseUrl = payload.optString("serviceBaseUrl", "").trim().trimEnd('/')
                )
            } catch (_: Exception) {
                AndroidRuntimeConfig(serviceBaseUrl = "")
            }
        }
    }
}
