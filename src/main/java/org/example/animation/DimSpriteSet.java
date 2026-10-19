package org.example.animation;

import javafx.scene.image.Image;

import java.util.EnumMap;
import java.util.Map;

/**
 * Contiene los sprites YA CONVERTIDOS a Image de JavaFX para la etapa
 * actual, indexados por función (SpriteRole) en vez de por posición
 * numérica cruda. Quien lee la DIM (VB-DIM-Reader) es responsable de
 * construir esto — convierte cada sprite de la etapa a Image y lo asocia
 * al SpriteRole que le corresponde según el mapeo de las secciones 05/06/07:
 *
 *   Baby I  (6 sprites):  0=NAME 1=IDLE_1 2=IDLE_2 3=WALK_1 4=VICTORY 5=SLEEP
 *   Baby II (7 sprites):  ... + 6=SPLASH
 *   Child+  (14 sprites): 0=NAME 1=IDLE_1 2=IDLE_2 3=WALK_1 4=WALK_2 5=RUN_1
 *                         6=RUN_2 7=TRAIN_1 8=TRAIN_2 9=VICTORY 10=SLEEP
 *                         11=ATTACK 12=DODGE 13=SPLASH
 *
 * Esta clase no sabe nada de VB-DIM-Reader ni del .bin — solo almacena el
 * resultado ya resuelto. Así, ajustar el lector DIM nunca obliga a tocar
 * el sistema de movimiento/cuidado/menú, y viceversa.
 */
public class DimSpriteSet {

    private final VPetStageTier stage;
    private final Map<SpriteRole, Image> sprites = new EnumMap<>(SpriteRole.class);
    private final int canvasWidth;
    private final int canvasHeight;

    public DimSpriteSet(VPetStageTier stage, int canvasWidth, int canvasHeight) {
        this.stage = stage;
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
    }

    public void put(SpriteRole role, Image image) {
        sprites.put(role, image);
    }

    public Image get(SpriteRole role) {
        Image image = sprites.get(role);
        if (image == null) {
            throw new IllegalStateException("Falta el sprite para " + role + " en la etapa " + stage
                    + ". Sprites cargados: " + sprites.keySet());
        }
        return image;
    }

    public boolean has(SpriteRole role) { return sprites.containsKey(role); }
    public VPetStageTier getStage() { return stage; }
    public int getCanvasWidth() { return canvasWidth; }
    public int getCanvasHeight() { return canvasHeight; }
}