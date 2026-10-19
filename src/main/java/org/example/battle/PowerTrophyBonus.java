package org.example.battle;

/**
 * Bono de stats por Power Trophies del mod "Digimon Link Pendulum" (Comic
 * Munch / Cyanic). Fuente: "Digimon Link Pendulum Research.pdf", secciones
 * "Trophy Types" y "27. VS DIM": cada 10 Power Trophies = +50% DP, +25% HP,
 * +1 AP, y aplica también a VS DIM.
 *
 * Verificado contra el propio VB con MagnaKidmon (35 trofeos -> 3 bloques):
 * DP 50 -> +75, HP 12 -> +9, AP 8 -> +3.
 *
 * Supuesto NO confirmado por el documento: redondeo hacia abajo cuando el
 * porcentaje no da entero. El tope de 120 es decisión nuestra (del usuario).
 */
public final class PowerTrophyBonus {

    public static final int TROPHIES_PER_TIER = 10;
    /**
     * Tope decidido por el usuario (2026-09-26): 120 puntos = rango S; más
     * trofeos ya no suben el bono (máximo 12 bloques).
     */
    public static final int MAX_TROPHIES = 120;

    public final int tiers;
    public final int dpBonus;
    public final int hpBonus;
    public final int apBonus;

    private PowerTrophyBonus(int tiers, int dpBonus, int hpBonus, int apBonus) {
        this.tiers = tiers;
        this.dpBonus = dpBonus;
        this.hpBonus = hpBonus;
        this.apBonus = apBonus;
    }

    public static PowerTrophyBonus forTrophies(int powerTrophies, int baseDp, int baseHp) {
        int tiers = Math.min(Math.max(0, powerTrophies), MAX_TROPHIES) / TROPHIES_PER_TIER;
        return new PowerTrophyBonus(tiers,
                baseDp * tiers / 2,   // +50% por bloque
                baseHp * tiers / 4,   // +25% por bloque
                tiers);               // +1 por bloque
    }

    /** Combatant con el bono aplicado; atributo, ataques y Activity Type intactos. */
    public BattleEngine.Combatant applyTo(BattleEngine.Combatant base) {
        return new BattleEngine.Combatant(base.dp + dpBonus, base.hp + hpBonus, base.ap + apBonus,
                base.attribute, base.smallAttackId, base.bigAttackId, base.activityType);
    }
}
