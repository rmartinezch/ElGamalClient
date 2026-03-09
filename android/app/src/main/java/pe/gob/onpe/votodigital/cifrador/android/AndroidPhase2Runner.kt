package pe.gob.onpe.votodigital.cifrador.android

import com.verificatum.vecj.VEC
import com.verificatum.vmgj.VMG
import java.math.BigInteger

class AndroidPhase2Runner {

    data class RuntimeCheck(
        val abi: String,
        val hasVecj: Boolean,
        val hasVmgj: Boolean,
        val curveCount: Int,
        val legendreValue: Int?,
        val message: String
    )

    fun runNativeSmokeTest(): RuntimeCheck {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        var hasVecj = false
        var hasVmgj = false
        var curveCount = 0
        var legendreValue: Int? = null
        val failures = mutableListOf<String>()

        try {
            curveCount = VEC.getCurveNames().size
            hasVecj = curveCount > 0
        } catch (e: Throwable) {
            failures += "vecj: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
        }

        try {
            legendreValue = VMG.legendre(BigInteger.TWO, BigInteger.valueOf(23L))
            hasVmgj = true
        } catch (e: Throwable) {
            failures += "vmgj: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
        }

        val message = buildString {
            append("ABI detectada: ")
            append(abi)
            append("\nvecj: ")
            append(if (hasVecj) "OK" else "ERROR")
            append(" (curvas=")
            append(curveCount)
            append(")")
            append("\nvmgj: ")
            append(if (hasVmgj) "OK" else "ERROR")
            append(" (legendre(2,23)=")
            append(legendreValue ?: "n/a")
            append(")")
            if (failures.isEmpty()) {
                append("\nPrueba JNI Android completada.")
            } else {
                append("\nErrores:")
                failures.forEach { failure ->
                    append("\n- ")
                    append(failure)
                }
            }
        }

        return RuntimeCheck(abi, hasVecj, hasVmgj, curveCount, legendreValue, message)
    }
}
