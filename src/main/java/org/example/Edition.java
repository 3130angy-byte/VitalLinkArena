package org.example;

/** Nombre y versión del programa. */
public final class Edition {

    /** VitalLink Arena: tu Digimon del Vital Bracelet en la PC (llega y vuelve con una VS DIM). */
    public static final String PRODUCT = "VitalLink Arena";
    /** Versión con Versionado Semántico (SemVer): 0.4.0 = 4.ª serie, beta pública 2 (VS Online v13: combates en vivo y ranking). */
    public static final String VERSION = "0.4.0-beta.2";
    /** Lo que se muestra: "VitalLink Arena v0.4.0-beta.2". */
    public static final String LABEL = PRODUCT + " v" + VERSION;

    private Edition() {}
}
