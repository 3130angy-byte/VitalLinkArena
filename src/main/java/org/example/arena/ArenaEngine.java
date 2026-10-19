package org.example.arena;

import org.example.battle.BattleEngine;

import java.util.Random;

/**
 * Reglas de la ARENA, sin nada de JavaFX (se puede probar sola). Modo 2 vs 2
 * inspirado en la app Vital Bracelet Arena, cuyas fórmulas exactas nunca se
 * publicaron; estas son nuestras (valores en ArenaConfig). Reglas v2
 * (2026-10-01, videos de la app + decisiones del usuario):
 *
 *  - Cada lado tiene 2 Digimon; pelea el ACTIVO, el otro espera.
 *  - ATAQUE: el atacante encadena un COMBO en un tiempo fijo (5 números a la
 *    vista; el acertado lo reemplaza el siguiente). El combo da un BONO DE AP
 *    con tope (13 ≈ +36 %, como en el video). Con 10 o más = BIG ATTACK.
 *  - NO HAY FALLOS (decisión del usuario): el DP funciona como "BP" y sube o
 *    baja el daño con la misma fórmula del VB (DP + atributo,
 *    BattleEngine.hitRate): 50 % = x1, más = más daño, menos = menos.
 *  - Stats convertidos como la app (ArenaFighter): DP x120 = BP, HP x400, AP x150.
 *  - Daño = AP x dano.factor x (1 + bono de combo) x BP x defensa.
 *  - Al ser atacado se elige (como la app): DEFENSE = minijuego de la barra, o
 *    PROTECT = el compañero aparece un momento y RECIBE el golpe (sin minijuego,
 *    con su propio BP); el activo sigue siendo el mismo. Si el compañero cae, no
 *    hay cambio. (La app además da +BP con habilidades del BE: aún no.)
 *  - DEFENSA: el defensor detiene una barra; "distancia al centro" 0..1.
 *    Zona perfecta / buena / fuera = el daño se multiplica por
 *    danoPerfecta / danoBuena / 1.
 *  - W-ATTACK: con el medidor lleno (100) y el compañero en pie, atacan los
 *    dos: (AP activo + AP compañero) x multiplicador x daño base (la defensa
 *    sí lo reduce). Vacía el medidor.
 *  - GUTS: a veces (guts.probabilidad) un golpe de KO deja al Digimon con 1 HP.
 *  - El medidor sube por cada número del combo y por vida perdida.
 *  - CAMBIAR gasta el turno. Si el activo cae, entra solo el compañero.
 *  - Gana el lado que deja a los 2 rivales en 0.
 */
public final class ArenaEngine {

    public static final int PLAYER = 0, CPU = 1;
    public static final double GAUGE_FULL = 100;

    /**
     * Resultado de un ataque, para que la pantalla lo anime tal cual (nunca recalcula nada).
     * {@code connected} = false solo cuando el turno se PASÓ (sin elegir a tiempo): no hay golpe.
     * {@code apBonus} = bono de AP del combo en % (para el texto "AP +36%").
     * {@code protect} = el golpe lo recibió el COMPAÑERO del defensor (PROTECT), no su activo.
     */
    public record Hit(int attackerSide, boolean wAttack, int combo, boolean connected, int damage,
                      boolean defenderFainted, boolean defenderReplaced,
                      boolean guts, boolean big, int apBonus, boolean protect) {}

    private final ArenaConfig config;
    private final Random random;
    private final ArenaFighter[][] teams;
    private final int[] active = {0, 0};
    private final double[] gauge = {0, 0};

    public ArenaEngine(ArenaConfig config, Random random, ArenaFighter[] playerTeam, ArenaFighter[] cpuTeam) {
        if (playerTeam.length != 2 || cpuTeam.length != 2) throw new IllegalArgumentException("La ARENA es 2 vs 2.");
        this.config = config;
        this.random = random;
        this.teams = new ArenaFighter[][]{playerTeam, cpuTeam};
    }

    public ArenaConfig config() { return config; }
    public ArenaFighter fighter(int side, int index) { return teams[side][index]; }
    public int activeIndex(int side) { return active[side]; }
    public ArenaFighter active(int side) { return teams[side][active[side]]; }
    public double gauge(int side) { return gauge[side]; }

    /** El compañero en espera, o null si ya cayó. */
    public ArenaFighter partner(int side) {
        ArenaFighter p = teams[side][1 - active[side]];
        return p.fainted() ? null : p;
    }

    public boolean canSwitch(int side) { return partner(side) != null; }

    public boolean wAttackReady(int side) { return gauge[side] >= GAUGE_FULL && partner(side) != null; }

    /** PLAYER o CPU si ya ganó alguien; -1 si la pelea sigue. */
    public int winner() {
        for (int side = 0; side < 2; side++) {
            if (teams[1 - side][0].fainted() && teams[1 - side][1].fainted()) return side;
        }
        return -1;
    }

    /** "% de acierto" del VB del activo de {@code side} contra el activo rival (DP + atributo); aquí se usa como BP. */
    public double hitRate(int side) {
        ArenaFighter a = active(side), d = active(1 - side);
        return BattleEngine.hitRate(a.bp, a.attribute, d.bp, d.attribute); // la fórmula usa la proporción: BP o DP dan lo mismo
    }

    /** Multiplicador de daño por BP: 50 % = x1; con peso 1, de x0.5 (0 %) a x1.5 (100 %). */
    public double bpFactor(int side) {
        return bpFactor(active(side), active(1 - side));
    }

    private double bpFactor(ArenaFighter attacker, ArenaFighter defender) {
        double rate = BattleEngine.hitRate(attacker.bp, attacker.attribute, defender.bp, defender.attribute);
        return Math.max(0.25, 1 + config.bpWeight * (rate - 50) / 100.0);
    }

    /** Bono de AP del combo (0..bonoMaximo), con rendimiento decreciente. */
    public double comboBonus(int combo) {
        return config.comboBonusMax * (1 - Math.exp(-Math.max(0, combo) / config.comboBonusCurve));
    }

    public boolean isBig(int combo) {
        return combo >= config.comboForBig;
    }

    public void switchActive(int side) {
        if (!canSwitch(side)) return;
        active[side] = 1 - active[side];
    }

    /**
     * @param combo    números encadenados (se ignora en el W-ATTACK)
     * @param defense  distancia al centro al detener la barra (0 = perfecto, 1 = no defendió)
     */
    public Hit attack(int side, int combo, double defense, boolean wAttack) {
        return attack(side, combo, defense, wAttack, false);
    }

    /**
     * @param protect  el defensor eligió PROTECT: recibe el golpe su compañero (sin
     *                 minijuego: {@code defense} se ignora). Sin compañero en pie, no aplica.
     */
    public Hit attack(int side, int combo, double defense, boolean wAttack, boolean protect) {
        if (wAttack && !wAttackReady(side)) wAttack = false;
        if (protect && partner(1 - side) == null) protect = false;
        if (protect) defense = 1.0; // PROTECT no tiene minijuego
        ArenaFighter attacker = active(side);
        ArenaFighter defender = protect ? partner(1 - side) : active(1 - side);
        combo = wAttack ? 0 : Math.max(0, Math.min(config.comboMax, combo));

        double bonus;
        double ap;
        if (wAttack) {
            bonus = 0;
            ap = (attacker.ap + partner(side).ap) * config.wAttackMultiplier;
            gauge[side] = 0;
        } else {
            bonus = comboBonus(combo);
            ap = attacker.ap;
            gauge[side] = Math.min(GAUGE_FULL, gauge[side] + combo * config.gaugePerCombo);
        }

        double raw = ap * config.damageFactor * (1 + bonus) * bpFactor(attacker, defender) * defenseFactor(defense);
        int damage = (int) Math.max(1, Math.round(raw));
        boolean guts = false;
        if (damage >= defender.hp && defender.hp > 1 && random.nextDouble() < config.gutsChance) {
            damage = defender.hp - 1; // GUTS!!!: aguanta con 1 HP
            guts = true;
        }
        damage = Math.min(damage, defender.hp);
        defender.hp -= damage;
        gauge[1 - side] = Math.min(GAUGE_FULL,
                gauge[1 - side] + damage * 100.0 / defender.maxHp * config.gaugePerHpPercentLost);

        boolean fainted = defender.fainted();
        boolean replaced = false;
        if (fainted && !protect && canSwitch(1 - side)) { // si cae el compañero que protegía, el activo sigue
            switchActive(1 - side);
            replaced = true;
        }
        return new Hit(side, wAttack, combo, true, damage, fainted, replaced, guts,
                wAttack || isBig(combo), (int) Math.round(bonus * 100), protect);
    }

    /** Turno perdido (no eligió a tiempo en la ARENA online): no hay golpe. */
    public Hit pass(int side) {
        return new Hit(side, false, 0, false, 0, false, false, false, false, 0, false);
    }

    /** Cuánto daño deja pasar la defensa. */
    public double defenseFactor(double distanceFromCenter) {
        double d = Math.abs(distanceFromCenter);
        if (d <= config.perfectZone) return config.perfectFactor;
        if (d <= config.goodZone) return config.goodFactor;
        return 1.0;
    }

    // ---------------------------------------------------------------- estado (ARENA online)

    /** Foto del estado para mandar por red: vida de los 4, activo y medidor de cada lado. */
    public org.json.JSONObject stateJson() {
        org.json.JSONArray hp = new org.json.JSONArray();
        for (int side = 0; side < 2; side++) {
            hp.put(new org.json.JSONArray().put(teams[side][0].hp).put(teams[side][1].hp));
        }
        return new org.json.JSONObject().put("hp", hp)
                .put("active", new org.json.JSONArray().put(active[0]).put(active[1]))
                .put("gauge", new org.json.JSONArray().put(Math.round(gauge[0])).put(Math.round(gauge[1])));
    }

    /**
     * El cliente online no calcula nada: copia el estado que manda el servidor.
     * {@code swapSides} = true si en el servidor eres el lado "b" (aquí siempre
     * eres el lado izquierdo, PLAYER).
     */
    public void applyState(org.json.JSONObject s, boolean swapSides) {
        org.json.JSONArray hp = s.getJSONArray("hp"), act = s.getJSONArray("active"), g = s.getJSONArray("gauge");
        for (int side = 0; side < 2; side++) {
            int from = swapSides ? 1 - side : side;
            teams[side][0].hp = Math.max(0, hp.getJSONArray(from).getInt(0));
            teams[side][1].hp = Math.max(0, hp.getJSONArray(from).getInt(1));
            active[side] = act.getInt(from) == 1 ? 1 : 0;
            gauge[side] = Math.max(0, Math.min(GAUGE_FULL, g.getDouble(from)));
        }
    }

    // ---------------------------------------------------------------- la máquina

    public enum CpuAction { ATTACK, W_ATTACK, SWITCH }

    /** Decisión simple: W-ATTACK si está listo; cambiar a veces si su activo está muy herido; si no, atacar. */
    public CpuAction cpuChoose() {
        if (wAttackReady(CPU) && random.nextDouble() < 0.8) return CpuAction.W_ATTACK;
        ArenaFighter me = active(CPU), other = partner(CPU);
        if (other != null && me.hpFraction() < 0.3 && other.hpFraction() > me.hpFraction() + 0.3
                && random.nextDouble() < 0.4) {
            return CpuAction.SWITCH;
        }
        return CpuAction.ATTACK;
    }

    public int cpuCombo() {
        return config.cpuComboLow + random.nextInt(Math.max(1, config.cpuComboHigh - config.cpuComboLow + 1));
    }

    /**
     * ¿La máquina usa PROTECT? A veces, si su activo está muy herido y el
     * compañero está mucho mejor (el compañero aguanta el golpe por él).
     */
    public boolean cpuProtects() {
        ArenaFighter me = active(CPU), other = partner(CPU);
        return other != null && me.hpFraction() < 0.35 && other.hpFraction() > me.hpFraction() + 0.3
                && random.nextDouble() < 0.5;
    }

    /** Qué tan bien se defiende la máquina de tu ataque (distancia al centro, al azar). */
    public double cpuDefense() {
        return random.nextDouble();
    }
}
