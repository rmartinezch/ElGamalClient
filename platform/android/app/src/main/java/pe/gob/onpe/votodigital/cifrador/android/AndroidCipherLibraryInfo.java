package pe.gob.onpe.votodigital.cifrador.android;

public final class AndroidCipherLibraryInfo {

    public static final String versionName = BuildConfig.CIPHER_LIBRARY_VERSION;
    public static final String buildTimestamp = BuildConfig.CIPHER_LIBRARY_BUILD_TIMESTAMP;
    public static final String rngSupport = BuildConfig.CIPHER_LIBRARY_RNG_SUPPORT;

    private AndroidCipherLibraryInfo() {
        throw new UnsupportedOperationException("Esta clase no debe ser instanciada.");
    }
}

