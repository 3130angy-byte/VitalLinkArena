package org.example.battle;

import org.example.chat.AppPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Vital Values que gana o pierde el Digimon por batalla, según la ETAPA DEL
 * RIVAL. Lo observado por el usuario en el VB real: Ultimate +500, Perfect
 * +200, Adult +80; Child +30 es PROVISIONAL (sin observar). Derrota: el
 * castigo real no se identificó; decisión de diseño: restar la misma
 * cantidad (PROVISIONAL). Empate: 0.
 *
 * Todo lo provisional es configurable (decisión del usuario), en
 * D:\DigimonProjectData\config\recompensas.properties (se crea con estos
 * valores la primera vez).
 */
public final class VitalRewards {

    static final Path FILE = AppPaths.config().resolve("recompensas.properties");

    /** Índice = etapa cruda de la DIM (2 Child .. 5 Ultimate); Baby no pelea. */
    private final int[] win = new int[6];
    private final int[] loss = new int[6];
    /** Tope de Vital Values al escribir la VS DIM (SUPUESTO: 9999, lo que cabe en 4 cifras del VB). */
    public final int maxVitalValues;

    private VitalRewards(Properties p) {
        String[] names = {null, null, "Child", "Adult", "Perfect", "Ultimate"};
        int[] defaults = {0, 0, 30, 80, 200, 500};
        for (int stage = 2; stage <= 5; stage++) {
            win[stage] = intOf(p, "victoria." + names[stage], defaults[stage]);
            loss[stage] = intOf(p, "derrota." + names[stage], defaults[stage]);
        }
        maxVitalValues = intOf(p, "vitalValues.maximo", 9999);
    }

    public static VitalRewards load() {
        Properties p = new Properties();
        if (!Files.exists(FILE)) writeDefaults();
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            System.out.println("[RECOMPENSAS] No se pudo leer " + FILE + ", valores por defecto: " + e.getMessage());
        }
        return new VitalRewards(p);
    }

    /** Cambio de Vital Values por una batalla: + si ganó, - si perdió, 0 en empate. */
    public int deltaFor(BattleOutcome outcome, int rivalStage) {
        int stage = Math.max(2, Math.min(5, rivalStage));
        return switch (outcome) {
            case WIN -> win[stage];
            case LOSS -> -loss[stage];
            case DRAW -> 0;
        };
    }

    public enum BattleOutcome { WIN, LOSS, DRAW }

    private static void writeDefaults() {
        String text = """
                # Vital Values por batalla según la ETAPA DEL RIVAL (se aplican al retirar al Digimon).
                # Observado en el VB real: Ultimate 500, Perfect 200, Adult 80. Child 30 = PROVISIONAL.
                victoria.Child=30
                victoria.Adult=80
                victoria.Perfect=200
                victoria.Ultimate=500
                # Derrota: el castigo real no se conoce; por ahora se resta lo mismo (PROVISIONAL).
                derrota.Child=30
                derrota.Adult=80
                derrota.Perfect=200
                derrota.Ultimate=500
                # Tope al escribir la VS DIM de vuelta (supuesto: 9999).
                vitalValues.maximo=9999
                """;
        try {
            Files.writeString(FILE, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[RECOMPENSAS] No se pudo crear " + FILE + ": " + e.getMessage());
        }
    }

    private static int intOf(Properties p, String key, int def) {
        try {
            return Math.max(0, Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
