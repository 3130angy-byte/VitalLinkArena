package org.example.animation;

/**
 * Nivel de etapa a efectos de sprites/menú/movimiento (secciones 04-07).
 * Solo decide "cuántos sprites y qué botones corresponden" a la forma fija
 * del Digimon (0.0.3 no tiene evolución).
 */
public enum VPetStageTier {
    BABY_I(6),
    BABY_II(7),
    CHILD_PLUS(14);

    private final int spriteCount;
    VPetStageTier(int spriteCount) { this.spriteCount = spriteCount; }
    public int getSpriteCount() { return spriteCount; }
}