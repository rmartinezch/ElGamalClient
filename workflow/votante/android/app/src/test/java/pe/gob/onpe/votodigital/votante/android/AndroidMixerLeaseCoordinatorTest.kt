package pe.gob.onpe.votodigital.votante.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidMixerLeaseCoordinatorTest {

    private val coordinator = AndroidMixerLeaseCoordinator("", "mesa-047612")

    @Test
    fun canonicalBaseUrlShouldKeepReachableAuthorityWhenDiscoveryAnnouncesHostname() {
        assertEquals(
            "http://192.168.0.120:7040",
            coordinator.canonicalBaseUrl(
                "http://192.168.0.120:7040",
                "http://wsantivanez-hm:7040"
            )
        )
    }

    @Test
    fun canonicalEndpointUrlShouldKeepReachableAuthorityAndPreservePath() {
        assertEquals(
            "http://192.168.0.120:7040/api/public-key",
            coordinator.canonicalEndpointUrl(
                "http://192.168.0.120:7040",
                "http://wsantivanez-hm:7040/api/public-key",
                "/api/public-key"
            )
        )
    }

    @Test
    fun canonicalEndpointUrlShouldRespectAnnouncedUrlWhenAuthorityMatches() {
        assertEquals(
            "http://127.0.0.1:8892/api/discovery",
            coordinator.canonicalEndpointUrl(
                "http://127.0.0.1:8892",
                "http://127.0.0.1:8892/api/discovery",
                "/api/discovery"
            )
        )
    }

    @Test
    fun sanitizeRequestedAuxsidShouldKeepBlankValuesBlank() {
        assertEquals("", coordinator.sanitizeRequestedAuxsid(""))
        assertEquals("", coordinator.sanitizeRequestedAuxsid(null))
    }

    @Test
    fun resolvedOperationalAuxsidShouldPreferResolvedAuxsidFromEmissionContext() {
        val emissionContext = AndroidMixerLeaseCoordinator.EmissionContextInfo(
            ok = true,
            sessionId = "sesion-1",
            sessionName = "Servidor 1",
            sessionLabel = "Servidor 1",
            electionName = "Eleccion Verificatum",
            sid = "ONPE",
            requestedAuxsid = "",
            resolvedAuxsid = "default22",
            auxsid = "default2",
            auxsidChanged = true,
            accumulated = true,
            accumulatedFromAuxsid = "default2",
            acceptingVotes = true
        )

        assertEquals("default22", coordinator.resolvedOperationalAuxsid(emissionContext))
    }
}
