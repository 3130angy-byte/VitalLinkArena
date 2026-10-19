package org.example.battle;

import com.github.cfogrady.vb.dim.character.CharacterStats;
import org.example.dim.DimVPetData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BattleEngine {

    private static final int MAX_ROUNDS = 5;
    private static final int SUPER_HIT_AP_BONUS = 2;
    private static final int NONE_VALUE = 0xFFFF;
    private static final boolean DEBUG_POOL_LOG = true;
    private static final boolean DEBUG_ROUND_LOG = true;

    private final Random random = new Random();

    public static class Combatant {
        public final int dp, hp, ap, attribute;
        public final int smallAttackId, bigAttackId, activityType;

        public Combatant(int dp, int hp, int ap, int attribute,
                         int smallAttackId, int bigAttackId, int activityType) {
            this.dp = dp; this.hp = hp; this.ap = ap; this.attribute = attribute;
            this.smallAttackId = smallAttackId;
            this.bigAttackId = bigAttackId;
            this.activityType = activityType;
        }

        public Combatant(int dp, int hp, int ap, int attribute) {
            this(dp, hp, ap, attribute, -1, -1, 2);
        }
    }

    public enum Side { PLAYER, ENEMY }

    /** SMALL = ataque normal. BIG = movimiento especial -- su ronda fija la determina el Activity Type del atacante, sin azar. */
    public enum AttackType { SMALL, BIG }

    public static class RoundOutcome {
        public final int roundNumber;
        public final Side attacker;
        public final AttackType attackType;
        public final int attackId;
        public final int damage;
        public final int playerHpAfter;
        public final int enemyHpAfter;

        public RoundOutcome(int roundNumber, Side attacker, AttackType attackType, int attackId, int damage,
                            int playerHpAfter, int enemyHpAfter) {
            this.roundNumber = roundNumber;
            this.attacker = attacker;
            this.attackType = attackType;
            this.attackId = attackId;
            this.damage = damage;
            this.playerHpAfter = playerHpAfter;
            this.enemyHpAfter = enemyHpAfter;
        }
    }

    public static class BattleResult {
        public final boolean won;
        /** Empate real (solo en Batallas Oficiales: mismo HP y mismo daño total). Si es true, won es false. */
        public final boolean draw;
        public final int roundsPlayed;
        public final int playerHpRemaining;
        public final int enemyHpRemaining;
        public final List<RoundOutcome> rounds;
        public final int playerSlot;
        public final int enemySlot;

        public BattleResult(boolean won, int roundsPlayed, int playerHpRemaining, int enemyHpRemaining,
                            List<RoundOutcome> rounds, int playerSlot, int enemySlot) {
            this(won, false, roundsPlayed, playerHpRemaining, enemyHpRemaining, rounds, playerSlot, enemySlot);
        }

        public BattleResult(boolean won, boolean draw, int roundsPlayed, int playerHpRemaining, int enemyHpRemaining,
                            List<RoundOutcome> rounds, int playerSlot, int enemySlot) {
            this.won = won && !draw;
            this.draw = draw;
            this.roundsPlayed = roundsPlayed;
            this.playerHpRemaining = playerHpRemaining;
            this.enemyHpRemaining = enemyHpRemaining;
            this.rounds = rounds;
            this.playerSlot = playerSlot;
            this.enemySlot = enemySlot;
        }
    }

    public Combatant combatantFromSlot(DimVPetData dimData, int slot) {
        CharacterStats.CharacterStatsEntry entry = dimData.getCard().getCharacterStats().getCharacterEntries().get(slot);
        return new Combatant(entry.getDp(), entry.getHp(), entry.getAp(), entry.getAttribute(),
                entry.getSmallAttackId(), entry.getBigAttackId(), entry.getType());
    }

    public int pickOpponentSlot(DimVPetData dimData, int playerSlot) {
        return pickOpponentSlot(dimData, dimData.getStageForSlot(playerSlot), playerSlot);
    }

    /**
     * Sortea un rival de la tabla de dimData según la etapa del jugador.
     * excludeSlot = slot a saltar (el propio jugador si es de la misma DIM),
     * o -1 si el jugador viene de otra tarjeta (VS DIM). Si no hay
     * candidatos válidos devuelve excludeSlot como respaldo -- con
     * excludeSlot = -1 eso sería inválido, así que en ese caso se usa el
     * primer slot con sprites de Child o superior.
     */
    public int pickOpponentSlot(DimVPetData dimData, int playerStage, int excludeSlot) {
        int playerSlot = excludeSlot;
        boolean useSecondPool = playerStage >= 4;

        if (DEBUG_POOL_LOG) {
            System.out.println("[BATTLE POOL] playerSlot=" + playerSlot + " playerStage=" + playerStage
                    + " pool=" + (useSecondPool ? "SECOND" : "FIRST"));
        }

        List<? extends CharacterStats.CharacterStatsEntry> entries = dimData.getCard().getCharacterStats().getCharacterEntries();
        List<Integer> candidateSlots = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int totalWeight = 0;

        for (int slot = 0; slot < entries.size(); slot++) {
            if (slot == playerSlot) continue;

            int stage = dimData.getStageForSlot(slot);
            if (stage < 2) continue;

            int chance = useSecondPool ? entries.get(slot).getSecondPoolBattleChance()
                    : entries.get(slot).getFirstPoolBattleChance();

            if (chance <= 0 || chance == NONE_VALUE) continue;

            candidateSlots.add(slot);
            weights.add(chance);
            totalWeight += chance;

            if (DEBUG_POOL_LOG) {
                System.out.println("[CANDIDATE] slot=" + slot + " stage=" + stage + " chance=" + chance);
            }
        }

        if (totalWeight <= 0) {
            if (DEBUG_POOL_LOG) {
                System.out.println("[BATTLE SELECTED] sin candidatos válidos -- se usa playerSlot como respaldo.");
            }
            if (playerSlot >= 0) return playerSlot;
            for (int slot = 0; slot < entries.size(); slot++) {
                if (dimData.getStageForSlot(slot) >= 2) return slot;
            }
            return entries.size() - 1;
        }

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        int selected = candidateSlots.get(candidateSlots.size() - 1);
        for (int i = 0; i < candidateSlots.size(); i++) {
            cumulative += weights.get(i);
            if (roll < cumulative) { selected = candidateSlots.get(i); break; }
        }

        if (DEBUG_POOL_LOG) {
            CharacterStats.CharacterStatsEntry sel = entries.get(selected);
            System.out.println("[BATTLE SELECTED] enemySlot=" + selected
                    + " enemyStage=" + dimData.getStageForSlot(selected)
                    + " enemySmallAttackId=" + sel.getSmallAttackId()
                    + " enemyBigAttackId=" + sel.getBigAttackId());
        }

        return selected;
    }

    public BattleResult fight(Combatant player, Combatant enemy, int playerSlot, int enemySlot) {
        int playerHp = player.hp;
        int enemyHp = enemy.hp;

        double hitrate = computeHitrate(player, enemy);
        List<RoundOutcome> rounds = new ArrayList<>();

        int round = 0;
        for (; round < MAX_ROUNDS; round++) {
            int roundNumber = round + 1;
            int roll = random.nextInt(100);
            boolean playerLandsHit = roll <= hitrate;

            Side attacker = playerLandsHit ? Side.PLAYER : Side.ENEMY;
            Combatant attackerCombatant = playerLandsHit ? player : enemy;

            boolean isSpecialRound = isSuperHitRound(attackerCombatant.activityType, roundNumber);
            AttackType attackType = isSpecialRound ? AttackType.BIG : AttackType.SMALL;
            int attackId = isSpecialRound ? attackerCombatant.bigAttackId : attackerCombatant.smallAttackId;
            int damage = attackerCombatant.ap + (isSpecialRound ? SUPER_HIT_AP_BONUS : 0);

            if (playerLandsHit) enemyHp = Math.max(0, enemyHp - damage);
            else playerHp = Math.max(0, playerHp - damage);

            rounds.add(new RoundOutcome(roundNumber, attacker, attackType, attackId, damage, playerHp, enemyHp));

            if (DEBUG_ROUND_LOG) {
                System.out.println("[ROUND] round=" + roundNumber + " attacker=" + attacker
                        + " activityType=" + attackerCombatant.activityType
                        + " attackType=" + attackType + " attackId=" + attackId
                        + " damage=" + damage
                        + " playerHpAfter=" + playerHp + " enemyHpAfter=" + enemyHp);
            }

            if (playerHp <= 0 || enemyHp <= 0) { round++; break; }
        }

        boolean won;
        if (enemyHp <= 0 && playerHp > 0) won = true;
        else if (playerHp <= 0 && enemyHp > 0) won = false;
        else won = playerHp >= enemyHp;

        return new BattleResult(won, round, playerHp, enemyHp, rounds, playerSlot, enemySlot);
    }

    /** Stoic(0)→ronda1, Active(1)→2, Normal(2)→3, Indoor(3)→4, Lazy(4)→5 -- determinístico, sin azar, confirmado con DIM-Modifier. */
    private boolean isSuperHitRound(int activityType, int roundNumber) {
        return roundNumber == activityType + 1;
    }

    private double computeHitrate(Combatant player, Combatant enemy) {
        return hitRate(player.dp, player.attribute, enemy.dp, enemy.attribute);
    }

    /** % de acierto del VB: tu DP / (tu DP + DP rival) x 100, ±5 por atributo. También lo usa la ARENA. */
    public static double hitRate(int dp, int attribute, int enemyDp, int enemyAttribute) {
        double base = dp + enemyDp <= 0 ? 50 : (dp / (double) (dp + enemyDp)) * 100.0;
        base += attributeAdjustment(attribute, enemyAttribute);
        return Math.max(0, Math.min(100, base));
    }

    private static double attributeAdjustment(int you, int enemy) {
        if (you == 3 && enemy == 1) return 5;
        if (you == 1 && enemy == 2) return 5;
        if (you == 2 && enemy == 3) return 5;
        if (you == 1 && enemy == 3) return -5;
        if (you == 2 && enemy == 1) return -5;
        if (you == 3 && enemy == 2) return -5;
        return 0;
    }
}