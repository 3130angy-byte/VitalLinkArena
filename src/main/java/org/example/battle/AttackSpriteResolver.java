package org.example.battle;

import javafx.scene.image.Image;

import java.util.HashMap;
import java.util.Map;

/**
 * Resuelve smallAttackId/bigAttackId a los sprites genéricos reales del
 * firmware Vital Bracelet -- confirmado con DIM-Modifier: índice directo,
 * sin offset, para el rango de firmware (Small: 0-38, Big: 0-21).
 */
public class AttackSpriteResolver {

    private static final int SMALL_ATTACK_COUNT = 39; // atk_s_00 .. atk_s_38
    private static final int BIG_ATTACK_COUNT = 22;    // atk_l_00 .. atk_l_21

    private final Map<Integer, Image> smallAttackCache = new HashMap<>();
    private final Map<Integer, Image> bigAttackCache = new HashMap<>();

    public Image getSmallAttack(int smallAttackId) {
        if (smallAttackId < 0 || smallAttackId >= SMALL_ATTACK_COUNT) return null;
        return smallAttackCache.computeIfAbsent(smallAttackId,
                id -> loadOrNull(String.format("/attacks/atk_s_%02d.png", id)));
    }

    public Image getBigAttack(int bigAttackId) {
        if (bigAttackId < 0 || bigAttackId >= BIG_ATTACK_COUNT) return null;
        return bigAttackCache.computeIfAbsent(bigAttackId,
                id -> loadOrNull(String.format("/attacks/atk_l_%02d.png", id)));
    }

    private Image loadOrNull(String resourcePath) {
        var stream = getClass().getResourceAsStream(resourcePath);
        if (stream == null) {
            System.out.println("AttackSpriteResolver: no se encontró " + resourcePath);
            return null;
        }
        return new Image(stream);
    }

    // ---------- TEMPORAL - diagnóstico de arranque, quitar la llamada en Main.java una vez confirmado ----------

    public void runDiagnostics() {
        System.out.println("[ATTACK SPRITES] Verificando recursos en /attacks/ ...");

        int smallFound = 0;
        for (int id = 0; id < SMALL_ATTACK_COUNT; id++) {
            String path = String.format("/attacks/atk_s_%02d.png", id);
            boolean exists = getClass().getResourceAsStream(path) != null;
            if (exists) smallFound++;
            System.out.println("[ATTACK SPRITES] " + path + " -> " + (exists ? "ENCONTRADO" : "FALTA"));
        }

        int bigFound = 0;
        for (int id = 0; id < BIG_ATTACK_COUNT; id++) {
            String path = String.format("/attacks/atk_l_%02d.png", id);
            boolean exists = getClass().getResourceAsStream(path) != null;
            if (exists) bigFound++;
            System.out.println("[ATTACK SPRITES] " + path + " -> " + (exists ? "ENCONTRADO" : "FALTA"));
        }

        System.out.println("[ATTACK SPRITES] RESUMEN Small: " + smallFound + "/" + SMALL_ATTACK_COUNT
                + " -- Big: " + bigFound + "/" + BIG_ATTACK_COUNT);

        System.out.println("[ATTACK SPRITES] --- Carga de IDs conocidos ---");
        for (int id : new int[]{0, 1, 5, 20, 38}) {
            logSample("Small", id, getSmallAttack(id));
        }
        for (int id : new int[]{0, 1, 10, 21}) {
            logSample("Big", id, getBigAttack(id));
        }

        System.out.println("[ATTACK SPRITES] --- Prueba de ID inválido (debe fallar controlado) ---");
        Image invalidSmall = getSmallAttack(999);
        System.out.println("[ATTACK SPRITES] Small id=999 -> "
                + (invalidSmall == null ? "null (correcto)" : "ERROR: no debería haber devuelto imagen"));
        Image invalidBig = getBigAttack(-1);
        System.out.println("[ATTACK SPRITES] Big id=-1 -> "
                + (invalidBig == null ? "null (correcto)" : "ERROR: no debería haber devuelto imagen"));
    }

    private void logSample(String label, int id, Image img) {
        if (img == null) {
            System.out.println("[ATTACK SPRITES] " + label + " id=" + id + " -> ERROR: imagen null");
        } else {
            System.out.println("[ATTACK SPRITES] " + label + " id=" + id + " -> OK, "
                    + img.getWidth() + "x" + img.getHeight()
                    + (img.isError() ? " (PERO img.isError()=true, revisar archivo)" : ""));
        }
    }
}