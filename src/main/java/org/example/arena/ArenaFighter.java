package org.example.arena;

import com.github.cfogrady.vb.dim.sprite.SpriteData;
import javafx.scene.image.Image;

import org.example.battle.BattleEngine;
import org.example.dim.DimSpriteImageFactory;
import org.example.dim.DimVPetData;

import java.util.List;

/**
 * Un Digimon en la ARENA: sus stats CONVERTIDOS como lo hacía la app Vital
 * Bracelet Arena (dato del usuario, 2026-10-02, definitivo):
 *   DP x 120 = BP,  HP x 400 = HP,  AP x 150 = AP
 * (p. ej. HP 8 de la DIM = 3200, como los ~3080 de los videos de la app), su
 * vida actual y los sprites que la pelea necesita. Solo Child o superior (los
 * Baby no tienen sprites de ATTACK/DODGE).
 */
public final class ArenaFighter {

    private static final int CANVAS_W = 64;
    private static final int CANVAS_H = 56;

    /** Conversión de la app (definitiva, no es configurable). */
    public static final int BP_PER_DP = 120, HP_PER_HP = 400, AP_PER_AP = 150;

    public final String name;
    /** Ya convertidos: BP (= DP x 120) y AP (= AP x 150). */
    public final int bp, ap, attribute, stage;
    /** Ya convertido: HP x 400. */
    public final int maxHp;
    public final boolean lent; // compañero prestado de una DIM card
    /** Ataques del firmware (AttackSpriteResolver) que la pantalla dispara. -1 = sin dato (no se dibuja proyectil). */
    public final int smallAttack, bigAttack;
    int hp;
    /** Premio del campeón de un torneo encendido: la pantalla lo dibuja al doble (solo adorno, no cambia la pelea). */
    public boolean big;

    final Image idle1, idle2, attack, dodge, nameImage;

    ArenaFighter(String name, int bp, int maxHp, int ap, int attribute, int stage, boolean lent,
                 int smallAttack, int bigAttack,
                 Image idle1, Image idle2, Image attack, Image dodge, Image nameImage) {
        this.name = name;
        this.bp = bp;
        this.maxHp = Math.max(1, maxHp);
        this.hp = this.maxHp;
        this.ap = ap;
        this.attribute = attribute;
        this.stage = stage;
        this.lent = lent;
        this.smallAttack = smallAttack;
        this.bigAttack = bigAttack;
        this.idle1 = idle1;
        this.idle2 = idle2;
        this.attack = attack;
        this.dodge = dodge;
        this.nameImage = nameImage;
    }

    /** Desde cualquier DIM (normal o VS DIM ya cargada) y su slot; convierte los stats. */
    public static ArenaFighter fromDim(DimVPetData card, int slot, String name, boolean lent, ArenaConfig config) {
        BattleEngine.Combatant c = new BattleEngine().combatantFromSlot(card, slot);
        List<SpriteData.Sprite> s = card.getSpritesForSlot(slot);
        // Orden Child+: NAME 0, IDLE_1 1, IDLE_2 2 ... ATTACK 11, DODGE 12.
        return new ArenaFighter(name, c.dp * BP_PER_DP, c.hp * HP_PER_HP, c.ap * AP_PER_AP, c.attribute,
                card.getStageForSlot(slot), lent, c.smallAttackId, c.bigAttackId,
                img(s.get(1)), img(s.get(2)), img(s.get(11)), img(s.get(12)),
                DimSpriteImageFactory.toNativeImage(s.get(0)));
    }

    /** Para el SERVIDOR de la ARENA online: solo stats (sin imágenes). dp/hp/ap = los de la DIM; aquí se convierten. */
    public static ArenaFighter forServer(String name, int dp, int hp, int ap, int attribute, int stage,
                                         int smallAttack, int bigAttack, ArenaConfig config) {
        return new ArenaFighter(name, dp * BP_PER_DP, hp * HP_PER_HP, ap * AP_PER_AP, attribute, stage, false,
                smallAttack, bigAttack, null, null, null, null, null);
    }

    /**
     * Para el CLIENTE de la ARENA online: solo lo que la pantalla necesita.
     * BP/AP del rival nunca llegan (los ve solo el servidor), así que valen 0;
     * maxHp ya viene convertido por el servidor.
     */
    public static ArenaFighter forDisplay(String name, int maxHp, int attribute, int stage, int smallAttack, int bigAttack,
                                          Image idle1, Image idle2, Image attack, Image dodge, Image nameImage) {
        return new ArenaFighter(name, 0, maxHp, 0, attribute, stage, false, smallAttack, bigAttack,
                idle1, idle2, attack, dodge, nameImage);
    }

    /** Solo para pruebas del motor, sin JavaFX. Recibe los stats de la DIM y los convierte. */
    static ArenaFighter forTest(String name, int dp, int hp, int ap, int attribute) {
        return new ArenaFighter(name, dp * BP_PER_DP, hp * HP_PER_HP, ap * AP_PER_AP, attribute, 5, false,
                -1, -1, null, null, null, null, null);
    }

    private static Image img(SpriteData.Sprite sprite) {
        return DimSpriteImageFactory.toImage(sprite, CANVAS_W, CANVAS_H);
    }

    /** Lo marca para verse al doble (premio de torneo encendido). */
    public ArenaFighter big(boolean on) {
        this.big = on;
        return this;
    }

    public int hp() { return hp; }
    public boolean fainted() { return hp <= 0; }
    public double hpFraction() { return hp / (double) maxHp; }
}
