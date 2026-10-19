package org.example.behavior;

import org.example.battle.VitalRewards;
import org.example.battle.VitalRewards.BattleOutcome;

/**
 * Datos de progreso del Digimon (sin crianza: nada de hambre, suciedad,
 * corazones ni evolución -- depuración de 0.0.3). Vital Values y Power
 * Trophies llegan de la VS DIM; el récord de batallas se acumula en el
 * programa (Batalla aleatoria y Batallas Oficiales del VS Online). Al
 * RETIRAR al Digimon, el saldo se resume en UN reporte de batalla dentro de
 * la VS DIM (VsDimWriter.writeBattleReport): positivo = victoria, negativo =
 * derrota. Los Vital Values de aquí son una ESTIMACIÓN: el VB calcula los
 * reales al leer el reporte.
 *
 * El récord pendiente se guarda en la cápsula del Laboratorio después de
 * cada batalla (toProperties/restore) y se vacía al RETIRAR.
 */
public class DigimonProgress {

    private final int importedVitalValues;
    private final int powerTrophies;
    private int battles = 0;
    private int wins = 0;
    private int losses = 0;
    private int draws = 0;
    /** Suma de lo ganado y perdido en todas las batallas (puede ser negativa). */
    private int vitalDelta = 0;
    /** Etapa del rival más fuerte vencido / que lo venció (-1 = ninguno): va en el reporte de batalla. */
    private int strongestDefeatedStage = -1;
    private int strongestWinnerStage = -1;

    public DigimonProgress(int vitalValues, int powerTrophies) {
        this.importedVitalValues = Math.max(0, vitalValues);
        this.powerTrophies = Math.max(0, powerTrophies);
    }

    /** Lo que traía la VS DIM al importarlo. */
    public int getImportedVitalValues() { return importedVitalValues; }
    public int getPowerTrophies() { return powerTrophies; }
    public int getBattles() { return battles; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getDraws() { return draws; }
    public int getVitalDelta() { return vitalDelta; }

    /**
     * El récord pendiente (lo que aún no se devolvió al VB), para guardarlo en
     * la cápsula del Laboratorio: así sobrevive a un reemplazo en el
     * escritorio y a cerrar el programa.
     */
    public java.util.Properties toProperties() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("progreso.batallas", String.valueOf(battles));
        p.setProperty("progreso.ganadas", String.valueOf(wins));
        p.setProperty("progreso.perdidas", String.valueOf(losses));
        p.setProperty("progreso.empates", String.valueOf(draws));
        p.setProperty("progreso.saldo", String.valueOf(vitalDelta));
        p.setProperty("progreso.rivalMasFuerteVencido", String.valueOf(strongestDefeatedStage));
        p.setProperty("progreso.rivalMasFuerteQueLoVencio", String.valueOf(strongestWinnerStage));
        return p;
    }

    /** Recupera lo guardado por {@link #toProperties()}; lo que falte queda en cero. */
    public void restore(java.util.Properties p) {
        battles = intOf(p, "progreso.batallas", 0);
        wins = intOf(p, "progreso.ganadas", 0);
        losses = intOf(p, "progreso.perdidas", 0);
        draws = intOf(p, "progreso.empates", 0);
        vitalDelta = intOf(p, "progreso.saldo", 0);
        strongestDefeatedStage = intOf(p, "progreso.rivalMasFuerteVencido", -1);
        strongestWinnerStage = intOf(p, "progreso.rivalMasFuerteQueLoVencio", -1);
    }

    private static int intOf(java.util.Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Resumen para el reporte de batalla de la VS DIM (decisión del usuario:
     * UN reporte con el saldo total). Saldo positivo = victoria contra el
     * rival más fuerte que venció; negativo = derrota contra el más fuerte
     * que lo venció; sin batallas o saldo 0 = sin reporte (vacío).
     */
    public java.util.Optional<BattleReport> battleReport() {
        if (vitalDelta > 0 && strongestDefeatedStage >= 0) {
            return java.util.Optional.of(new BattleReport(true, strongestDefeatedStage));
        }
        if (vitalDelta < 0 && strongestWinnerStage >= 0) {
            return java.util.Optional.of(new BattleReport(false, strongestWinnerStage));
        }
        return java.util.Optional.empty();
    }

    public record BattleReport(boolean won, int rivalStage) {}

    /** Estimación de Vital Values tras el retiro (el valor real lo calcula el VB al leer el reporte). */
    public int getVitalValues(VitalRewards rewards) {
        return Math.max(0, Math.min(rewards.maxVitalValues, importedVitalValues + vitalDelta));
    }

    /** Registra una batalla; devuelve el cambio de Vital Values que produjo. */
    public int recordBattle(BattleOutcome outcome, int rivalStage, VitalRewards rewards) {
        int delta = rewards.deltaFor(outcome, rivalStage);
        battles++;
        switch (outcome) {
            case WIN -> {
                wins++;
                strongestDefeatedStage = Math.max(strongestDefeatedStage, rivalStage);
            }
            case LOSS -> {
                losses++;
                strongestWinnerStage = Math.max(strongestWinnerStage, rivalStage);
            }
            case DRAW -> draws++;
        }
        vitalDelta += delta;
        System.out.printf("[BATALLA] %s contra etapa %d: %+d VV (saldo %+d; %d batallas, %d-%d-%d)%n",
                outcome, rivalStage, delta, vitalDelta, battles, wins, losses, draws);
        return delta;
    }
}
