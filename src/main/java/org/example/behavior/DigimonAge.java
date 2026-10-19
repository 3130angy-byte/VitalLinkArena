package org.example.behavior;

/**
 * Edad del Digimon: la que traía del Vital Bracelet (la VS DIM no la guarda,
 * se le pregunta al usuario al importarlo) + los días que lleva en el
 * programa. Sin vida útil ni muerte (depuración de crianza en 0.0.3).
 */
public class DigimonAge {

    private static final long ONE_DAY_MILLIS = 24L * 60 * 60 * 1000;

    private final int importedAgeDays;
    private final long arrivedMillis = System.currentTimeMillis();

    public DigimonAge(int importedAgeDays) {
        this.importedAgeDays = Math.max(0, importedAgeDays);
    }

    public long getAgeDays() {
        return importedAgeDays + (System.currentTimeMillis() - arrivedMillis) / ONE_DAY_MILLIS;
    }
}
