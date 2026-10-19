package org.example.online.server;

import org.example.battle.BattleEngine;
import org.example.battle.PowerTrophyBonus;
import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Batallas Oficiales (NPC "Batalla oficial" de la sala), decididas por el
 * usuario:
 *  - un jugador se pone DISPONIBLE; los demás ven la lista de disponibles;
 *  - retar = el retado ve "[nombre] te ha retado..." y tiene 15 s para
 *    aceptar; sin respuesta = rechazo automático;
 *  - el retador elige el MODO: Batalla Libre (stats + bono de puntos, tope
 *    120; el bono solo existe en el online) o Batalla Original (stats de la
 *    DIM, sin bono); el retado ve cuál es antes de aceptar;
 *  - si acepta, el SERVIDOR calcula la pelea UNA sola vez (BattleEngine) y
 *    manda el mismo resultado a ambos -- nunca "ambos ganan" como en VB Arena;
 *  - empate de HP: gana quien hizo más daño total; si también empatan,
 *    empate real (documento de diseño).
 * Tras pelear, ambos quedan NO disponibles (vuelven a anotarse con el NPC).
 *
 * Todo el estado de retos se toca con el candado de esta clase.
 */
final class OfficialBattles {

    /** Si un cliente nunca avisa "battleDone", a los 90 s se le libera igual. */
    private static final int BATTLE_SAFETY_SECONDS = 90;
    /** v12: quien está en un torneo solo pelea sus combates del torneo (decisión del usuario). */
    static final String TOURNAMENT_LOCKED = "Estás en un torneo: solo peleas tus combates del torneo hasta que termine o"
            + " quedes eliminado.";
    /** ARENA de torneo en curso -> a quién avisar el ganador. */
    private final Map<OnlineArenaMatch, java.util.function.IntConsumer> tournamentArenas = new java.util.concurrent.ConcurrentHashMap<>();

    private record Challenge(int fromId, int toId, String mode, ScheduledFuture<?> timeout) {}

    private final VsServer server;
    private final BattleEngine engine = new BattleEngine();
    private final Map<Integer, Challenge> byChallenger = new HashMap<>();
    private final Map<Integer, Challenge> byTarget = new HashMap<>();
    /** ARENA 2 vs 2 online en curso, por id de jugador (sin el candado de esta clase: ver OnlineArenaMatch). */
    private final Map<Integer, OnlineArenaMatch> arenas = new java.util.concurrent.ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vs-retos");
        t.setDaemon(true);
        return t;
    });

    OfficialBattles(VsServer server, ServerConfig config) {
        this.server = server;
    }

    // ---------------------------------------------------------------- disponibles

    synchronized void setAvailable(ClientConnection c, boolean on) {
        if (on) {
            String problem = cannotBattle(c);
            if (problem == null && server.tournaments().isLocked(c)) problem = TOURNAMENT_LOCKED;
            if (problem != null) {
                c.send(Protocol.msg("availability").put("on", false).put("reason", problem));
                return;
            }
        }
        c.available = on;
        c.send(Protocol.msg("availability").put("on", on));
        VsServer.log(c.name + (on ? " quedó disponible" : " dejó de estar disponible") + " para Batallas Oficiales.");
    }

    synchronized void sendAvailableList(ClientConnection c) {
        JSONArray list = new JSONArray();
        for (ClientConnection p : server.players()) {
            if (p == c || !p.available || p.inBattle || p.digimon == null) continue;
            list.put(new JSONObject().put("id", p.id).put("name", p.name)
                    .put("species", p.digimon.optString("species")).put("rank", p.digimon.optString("rank"))
                    .put("copy", VsServer.isCopy(p.digimon)));
        }
        c.send(Protocol.msg("availableList").put("players", list));
    }

    // ---------------------------------------------------------------- retos

    synchronized void challenge(ClientConnection from, int targetId, String mode) {
        String problem = cannotBattle(from);
        if (problem == null && !Protocol.isValidMode(mode)) problem = "Modo de batalla desconocido.";
        ClientConnection target = server.player(targetId);
        if (problem == null && Protocol.MODE_ARENA.equals(mode)) {
            problem = OnlineArenaMatch.teamProblem(from);
            if (problem == null && target != null) problem = OnlineArenaMatch.teamProblem(target);
        }
        if (problem == null && server.tournaments().isLocked(from)) problem = TOURNAMENT_LOCKED;
        if (problem == null && target != null && server.tournaments().isLocked(target)) problem = target.name + " está en un torneo.";
        if (problem == null) {
            if (target == null || target == from) problem = "Ese jugador ya no está en la sala.";
            else if (!target.available) problem = target.name + " ya no está disponible.";
            else if (from.inBattle || target.inBattle) problem = "Alguno de los dos ya está en una batalla.";
            else if (involved(from.id) || involved(target.id)) problem = "Ya hay un reto pendiente con alguno de los dos.";
        }
        if (problem != null) {
            from.send(Protocol.msg("challengeEnded").put("reason", problem));
            return;
        }
        int fromId = from.id, toId = target.id;
        ScheduledFuture<?> timeout = timers.schedule(() -> expire(fromId, toId), Protocol.CHALLENGE_SECONDS, TimeUnit.SECONDS);
        Challenge ch = new Challenge(fromId, toId, mode, timeout);
        byChallenger.put(fromId, ch);
        byTarget.put(toId, ch);

        target.send(Protocol.msg("challenged").put("from", fromId).put("name", from.name)
                .put("species", from.digimon.optString("species")).put("rank", from.digimon.optString("rank"))
                .put("copy", VsServer.isCopy(from.digimon))
                .put("mode", mode).put("seconds", Protocol.CHALLENGE_SECONDS));
        from.send(Protocol.msg("challengePending").put("target", toId).put("name", target.name).put("mode", mode)
                .put("seconds", Protocol.CHALLENGE_SECONDS));
        VsServer.log(from.name + " retó a " + target.name + " (" + Protocol.modeName(mode) + ").");
    }

    synchronized void reply(ClientConnection target, int fromId, boolean accept) {
        Challenge ch = byTarget.get(target.id);
        if (ch == null || ch.fromId() != fromId) {
            target.send(Protocol.msg("challengeEnded").put("reason", "Ese reto ya no es válido."));
            return;
        }
        remove(ch);
        ClientConnection from = server.player(fromId);
        if (from == null) {
            target.send(Protocol.msg("challengeEnded").put("reason", "El retador salió de la sala."));
            return;
        }
        if (accept && (server.tournaments().isLocked(from) || server.tournaments().isLocked(target))) {
            String reason = "Uno de los dos entró a un torneo: el reto se canceló.";
            from.send(Protocol.msg("challengeEnded").put("reason", reason));
            target.send(Protocol.msg("challengeEnded").put("reason", reason));
            return;
        }
        if (!accept) {
            from.send(Protocol.msg("challengeEnded").put("reason", target.name + " rechazó el reto."));
            target.send(Protocol.msg("challengeEnded").put("reason", "Rechazaste el reto."));
            VsServer.log(target.name + " rechazó el reto de " + from.name + ".");
            return;
        }
        if (Protocol.MODE_ARENA.equals(ch.mode())) {
            OnlineArenaMatch match = new OnlineArenaMatch(this, from, target, timers);
            arenas.put(from.id, match);
            arenas.put(target.id, match);
            match.start();
            return;
        }
        runBattle(from, target, ch.mode(), null);
    }

    // ---------------------------------------------------------------- torneos (v12)

    /**
     * Un combate de torneo: sin reto ni "disponible" (los dos ya dijeron "listo"). {@code onWinner} recibe el id
     * del ganador (0 = empate, solo en Original/Libre); en la ARENA, al terminar la partida.
     */
    synchronized void startTournamentFight(ClientConnection a, ClientConnection b, String mode, String label,
                                           java.util.function.IntConsumer onWinner) {
        a.available = b.available = false;
        if (Protocol.MODE_ARENA.equals(mode)) {
            OnlineArenaMatch match = new OnlineArenaMatch(this, a, b, timers);
            match.tournament = label;
            tournamentArenas.put(match, onWinner);
            arenas.put(a.id, match);
            arenas.put(b.id, match);
            match.start();
            return;
        }
        onWinner.accept(runBattle(a, b, mode, label));
    }

    // ---------------------------------------------------------------- ARENA 2 vs 2 online

    void arenaAction(ClientConnection c, String action) {
        OnlineArenaMatch m = arenas.get(c.id);
        if (m != null) m.onAction(c, action);
    }

    void arenaCombo(ClientConnection c, int combo) {
        OnlineArenaMatch m = arenas.get(c.id);
        if (m != null) m.onCombo(c, combo);
    }

    void arenaDefense(ClientConnection c, double distance, boolean protect) {
        OnlineArenaMatch m = arenas.get(c.id);
        if (m != null) m.onDefense(c, distance, protect);
    }

    /** Terminó una ARENA: se suelta la partida; cada uno queda libre al avisar battleDone (o a los 90 s). */
    void arenaFinished(OnlineArenaMatch match, ClientConnection a, ClientConnection b, int winnerId) {
        java.util.function.IntConsumer onWinner = tournamentArenas.remove(match);
        if (onWinner != null) onWinner.accept(winnerId);
        arenas.remove(a.id, match);
        arenas.remove(b.id, match);
        timers.schedule(() -> {
            a.inBattle = false;
            b.inBattle = false;
        }, BATTLE_SAFETY_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void expire(int fromId, int toId) {
        Challenge ch = byChallenger.get(fromId);
        if (ch == null || ch.toId() != toId) return;
        remove(ch);
        String reason = "Sin respuesta en " + Protocol.CHALLENGE_SECONDS + " s: el reto se canceló.";
        sendIfPresent(fromId, reason);
        sendIfPresent(toId, reason);
    }

    synchronized void onLeave(ClientConnection c) {
        c.available = false;
        OnlineArenaMatch arena = arenas.get(c.id);
        if (arena != null) arena.onLeave(c);
        Challenge asChallenger = byChallenger.get(c.id);
        Challenge asTarget = byTarget.get(c.id);
        if (asChallenger != null) {
            remove(asChallenger);
            sendIfPresent(asChallenger.toId(), c.name + " salió de la sala: el reto se canceló.");
        }
        if (asTarget != null) {
            remove(asTarget);
            sendIfPresent(asTarget.fromId(), c.name + " salió de la sala: el reto se canceló.");
        }
    }

    synchronized void battleDone(ClientConnection c) {
        c.inBattle = false;
    }

    // ---------------------------------------------------------------- la pelea

    /** Una sola pelea, calculada aquí; "a" = retador, "b" = retado. Devuelve el id del ganador (0 = empate). */
    private int runBattle(ClientConnection a, ClientConnection b, String mode, String tournament) {
        boolean withBonus = Protocol.MODE_FREE.equals(mode);
        BattleEngine.Combatant ca = combatant(a, withBonus);
        BattleEngine.Combatant cb = combatant(b, withBonus);
        BattleEngine.BattleResult r = engine.fight(ca, cb, 0, 0);

        int damageA = 0, damageB = 0;
        JSONArray rounds = new JSONArray();
        for (BattleEngine.RoundOutcome o : r.rounds) {
            boolean byA = o.attacker == BattleEngine.Side.PLAYER;
            if (byA) damageA += o.damage;
            else damageB += o.damage;
            rounds.put(new JSONObject().put("round", o.roundNumber).put("attacker", byA ? "a" : "b")
                    .put("type", o.attackType.name()).put("attackId", o.attackId).put("damage", o.damage)
                    .put("hpA", o.playerHpAfter).put("hpB", o.enemyHpAfter));
        }
        // Regla de empate (documento de diseño): más HP gana; con el mismo HP, más daño
        // total; con el mismo daño, empate real. Nunca "gana quien mira".
        int hpA = r.playerHpRemaining, hpB = r.enemyHpRemaining;
        int winner;
        if (hpA != hpB) winner = hpA > hpB ? a.id : b.id;
        else if (damageA != damageB) winner = damageA > damageB ? a.id : b.id;
        else winner = 0;

        JSONObject msg = Protocol.msg("battle")
                .put("a", side(a, ca)).put("b", side(b, cb))
                .put("rounds", rounds).put("winner", winner).put("mode", mode);
        if (tournament != null) msg.put("tournament", tournament);
        a.inBattle = b.inBattle = true;
        a.available = b.available = false; // tras pelear, cada uno vuelve a anotarse con el NPC
        a.send(msg);
        b.send(msg);
        timers.schedule(() -> {
            a.inBattle = false;
            b.inBattle = false;
        }, BATTLE_SAFETY_SECONDS, TimeUnit.SECONDS);

        String outcome = winner == 0 ? "empate" : "ganó " + (winner == a.id ? a.name : b.name);
        VsServer.log(Protocol.modeName(mode) + ": " + a.name + " vs " + b.name + " -> " + outcome
                + " (HP " + hpA + "-" + hpB + ", daño " + damageA + "-" + damageB + ")");
        return winner;
    }

    /** Lo que cada cliente necesita para ANIMAR la pelea: nunca DP ni AP del rival. */
    private static JSONObject side(ClientConnection c, BattleEngine.Combatant cb) {
        return new JSONObject().put("id", c.id).put("attribute", cb.attribute)
                .put("maxHp", cb.hp).put("small", cb.smallAttackId);
    }

    /** Stats guardados del Digimon; en Batalla Libre, + bono de puntos (solo en el online, decisión del usuario). */
    private static BattleEngine.Combatant combatant(ClientConnection c, boolean withBonus) {
        int[] s = c.stats;
        BattleEngine.Combatant base = new BattleEngine.Combatant(s[0], s[1], s[2],
                c.digimon.optInt("attribute", 0), s[3], s[4], s[5]);
        if (!withBonus) return base;
        return PowerTrophyBonus.forTrophies(c.powerTrophies, base.dp, base.hp).applyTo(base);
    }

    // ---------------------------------------------------------------- auxiliares

    private static String cannotBattle(ClientConnection c) {
        if (c.digimon == null || c.stats == null) return "Necesitas traer a tu Digimon para pelear.";
        if (c.stage < Protocol.MIN_BATTLE_STAGE) return "Solo pelean Digimon de etapa Child o superior.";
        return null;
    }

    /** Peleando o con un reto pendiente: no puede cambiar su equipo. */
    synchronized boolean isBusy(ClientConnection c) {
        return c.inBattle || involved(c.id) || arenas.containsKey(c.id);
    }

    private boolean involved(int id) {
        return byChallenger.containsKey(id) || byTarget.containsKey(id);
    }

    private void remove(Challenge ch) {
        ch.timeout().cancel(false);
        byChallenger.remove(ch.fromId());
        byTarget.remove(ch.toId());
    }

    private void sendIfPresent(int id, String reason) {
        ClientConnection c = server.player(id);
        if (c != null) c.send(Protocol.msg("challengeEnded").put("reason", reason));
    }
}
