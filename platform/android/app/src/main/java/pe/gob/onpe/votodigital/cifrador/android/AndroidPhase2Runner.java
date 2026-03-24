package pe.gob.onpe.votodigital.cifrador.android;

import android.os.Build;
import com.verificatum.vecj.VEC;
import com.verificatum.vmgj.VMG;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class AndroidPhase2Runner {

    public RuntimeCheck runNativeSmokeTest() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        boolean hasVecj = false;
        boolean hasVmgj = false;
        int curveCount = 0;
        Integer legendreValue = null;
        List<String> failures = new ArrayList<>();

        try {
            curveCount = VEC.getCurveNames().length;
            hasVecj = curveCount > 0;
        } catch (Throwable error) {
            failures.add("vecj: " + error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "sin detalle" : error.getMessage()));
        }

        try {
            legendreValue = VMG.legendre(BigInteger.valueOf(2L), BigInteger.valueOf(23L));
            hasVmgj = true;
        } catch (Throwable error) {
            failures.add("vmgj: " + error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "sin detalle" : error.getMessage()));
        }

        StringBuilder message = new StringBuilder();
        message.append("ABI detectada: ").append(abi);
        message.append("\nvecj: ").append(hasVecj ? "OK" : "ERROR");
        message.append(" (curvas=").append(curveCount).append(")");
        message.append("\nvmgj: ").append(hasVmgj ? "OK" : "ERROR");
        message.append(" (legendre(2,23)=").append(legendreValue == null ? "n/a" : legendreValue).append(")");
        if (failures.isEmpty()) {
            message.append("\nPrueba JNI Android completada.");
        } else {
            message.append("\nErrores:");
            for (String failure : failures) {
                message.append("\n- ").append(failure);
            }
        }

        return new RuntimeCheck(abi, hasVecj, hasVmgj, curveCount, legendreValue, message.toString());
    }

    public static final class RuntimeCheck {
        private final String abi;
        private final boolean hasVecj;
        private final boolean hasVmgj;
        private final int curveCount;
        private final Integer legendreValue;
        private final String message;

        public RuntimeCheck(
                String abi,
                boolean hasVecj,
                boolean hasVmgj,
                int curveCount,
                Integer legendreValue,
                String message
        ) {
            this.abi = abi;
            this.hasVecj = hasVecj;
            this.hasVmgj = hasVmgj;
            this.curveCount = curveCount;
            this.legendreValue = legendreValue;
            this.message = message;
        }

        public String getAbi() {
            return abi;
        }

        public boolean getHasVecj() {
            return hasVecj;
        }

        public boolean getHasVmgj() {
            return hasVmgj;
        }

        public int getCurveCount() {
            return curveCount;
        }

        public Integer getLegendreValue() {
            return legendreValue;
        }

        public String getMessage() {
            return message;
        }
    }
}

