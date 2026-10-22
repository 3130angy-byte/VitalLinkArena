package org.example.online.server;

import org.example.arena.ArenaConfig;
import org.example.arena.ArenaEngine;
import org.example.arena.ArenaFighter;
import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Una ARENA 2 vs 2 online (protocolo v6). El SERVIDOR corre el motor
 * (ArenaEngine, mismas reglas que la ARENA local, sin bono de trofeos) y
 * decide turnos y daño; cada jugador solo juega su minijuego y manda el
 * resultado: el atacante su combo (números tocados en orden) y el defensor
 * la distancia al centro de la barra.
 *
 * Lado 0 = "a" (el que retó), lado 1 = "b" (el retado). Por turno:
 *   arenaTurn  → el dueño elige atacar / cambiar / W-ATTACK  (arenaAction)
 *   arenaAttack→ el atacante toca los números                 (arenaCombo)
 *   arenaDefend→ el defensor elige DEFENSE (barra) o PROTECT  (arenaDefense: distance, protect)
 *   arenaHit / arenaSwitch → ambos animan lo resuelto; turno del otro.
 * Sin respuesta a tiempo = pierde esa parte del turno (golpe fallido o sin
 * defensa). Si uno se va de la sala, gana el otro.
 *
 * OJO (anotado en CLAUDE.md): el combo y la defensa los informa el cliente;
 * el servidor solo los acota. Suficiente entre amigos; no a prueba de trampas.
 */
final class OnlineArenaMatch {

    private static final int ACTION_SECONDS = 25;
    private static final int MINIGAME_SECONDS = 14;

    private enum Phase { ACTION, COMBO, DEFENSE, OVER }

    private final OfficialBattles battles;
    private final ClientConnection[] players;
    private final ArenaEngine engine;
    private final ArenaConfig config;
    private final ScheduledExecutorService timers;
    private Phase phase = Phase.ACTION;
    private int turn = 0;
    private int combo;
    private boolean wAttack;
    private ScheduledFuture<?> timeout;
    /** v12: nombre del torneo si es un combate de torneo (null = reto normal). */
    volatile String tournament;
    /** v13: este combate en la pantalla "COMBATES EN VIVO" de la sala (quienes lo miran). */
    private Spectate.Fight spec;

    OnlineArenaMatch(OfficialBattles battles, ClientConnection a, ClientConnection b, ScheduledExecutorService timers) {
        this.battles = battles;
        this.players = new ClientConnection[]{a, b};
        this.timers = timers;
        this.config = ArenaConfig.load();
        this.engine = new ArenaEngine(config, new Random(), team(a), team(b));
    }

    private ArenaFighter[] team(ClientConnection c) {
        int[] s = c.stats, p = c.partnerStats;
        return new ArenaFighter[]{
                ArenaFighter.forServer(c.name, s[0], s[1], s[2], c.digimon.optInt("attribute", 0), c.stage, s[3], s[4], config),
                ArenaFighter.forServer(c.name + " (2)", p[0], p[1], p[2], c.partnerAttribute, c.partnerStage, p[3], p[4], config)};
    }

    /** ¿Tiene equipo para la ARENA? (puesto 1 y 2, ambos Child o superior). null = sí; si no, el motivo. */
    static String teamProblem(ClientConnection c) {
        if (c.stats == null || c.partnerStats == null) return c.name + " no tiene un Digimon en el puesto 2 (se elige en la PC).";
        if (c.stage < Protocol.MIN_BATTLE_STAGE || c.partnerStage < Protocol.MIN_BATTLE_STAGE) {
            return "En la ARENA solo pelean Digimon Child o superiores (los 2 de " + c.name + ").";
        }
        return null;
    }

    synchronized void start() {
        for (ClientConnection p : players) {
            p.inBattle = true;
            p.available = false;
        }
        JSONObject msg = Protocol.msg("arenaStart")
                .put("a", sideInfo(0)).put("b", sideInfo(1))
                .put("state", engine.stateJson())
                .put("actionSeconds", ACTION_SECONDS).put("minigameSeconds", MINIGAME_SECONDS);
        if (tournament != null) msg.put("tournament", tournament);
        send(0, msg);
        send(1, msg);
        spec = battles.spectate().arenaStarted(this, players[0], players[1], tournament);
        VsServer.log("ARENA 2 vs 2: " + players[0].name + " vs " + players[1].name);
        nextTurn(0);
    }

    private JSONObject sideInfo(int side) {
        JSONArray team = new JSONArray();
        for (int i = 0; i < 2; i++) {
            ArenaFighter f = engine.fighter(side, i);
            team.put(new JSONObject().put("maxHp", f.maxHp).put("attribute", f.attribute).put("stage", f.stage)
                    .put("small", f.smallAttack).put("big", f.bigAttack)); // solo para dibujar su ataque
        }
        return new JSONObject().put("id", players[side].id).put("team", team);
    }

    private void nextTurn(int side) {
        int winner = engine.winner();
        if (winner >= 0) {
            finish(winner, null);
            return;
        }
        turn = side;
        phase = Phase.ACTION;
        JSONObject msg = Protocol.msg("arenaTurn").put("side", sideName(side))
                .put("canSwitch", engine.canSwitch(side)).put("wReady", engine.wAttackReady(side))
                .put("state", engine.stateJson()).put("seconds", ACTION_SECONDS);
        send(0, msg);
        send(1, msg);
        toWatchers(msg);
        schedule(ACTION_SECONDS, this::actionTimedOut);
    }

    synchronized void onAction(ClientConnection c, String action) {
        if (phase != Phase.ACTION || players[turn] != c) return;
        cancelTimeout();
        switch (action) {
            case "switch" -> {
                if (!engine.canSwitch(turn)) {
                    passTurn(); // cambio imposible: pierde el turno
                    return;
                }
                engine.switchActive(turn);
                JSONObject msg = Protocol.msg("arenaSwitch").put("side", sideName(turn)).put("state", engine.stateJson());
                send(0, msg);
                send(1, msg);
                toWatchers(msg);
                nextTurn(1 - turn);
            }
            case "w" -> {
                if (!engine.wAttackReady(turn)) {
                    passTurn();
                    return;
                }
                wAttack = true;
                combo = 0;
                askDefense();
            }
            default -> { // "attack"
                wAttack = false;
                phase = Phase.COMBO;
                send(turn, Protocol.msg("arenaAttack").put("numbers", config.attackNumbers)
                        .put("seconds", config.comboSeconds));
                send(1 - turn, Protocol.msg("arenaWait").put("text", players[turn].name + " está atacando..."));
                // El combo dura combo.segundos + la animación de inicio y el resultado en el cliente.
                schedule((int) Math.ceil(config.comboSeconds) + 8, () -> onComboValue(0));
            }
        }
    }

    synchronized void onCombo(ClientConnection c, int value) {
        if (phase != Phase.COMBO || players[turn] != c) return;
        onComboValue(value);
    }

    private synchronized void onComboValue(int value) {
        if (phase != Phase.COMBO) return;
        cancelTimeout();
        combo = Math.max(0, Math.min(config.comboMax, value)); // reglas v2: sin fallos, combo 0 = golpe sin bono
        askDefense();
    }

    private void askDefense() {
        phase = Phase.DEFENSE;
        // El defensor elige: DEFENSE (minijuego) o PROTECT (recibe el golpe su compañero).
        send(1 - turn, Protocol.msg("arenaDefend").put("combo", combo).put("w", wAttack)
                .put("canProtect", engine.partner(1 - turn) != null).put("seconds", config.defenseSeconds));
        send(turn, Protocol.msg("arenaWait").put("text", players[1 - turn].name + " se está defendiendo..."));
        schedule(MINIGAME_SECONDS, () -> onDefenseValue(1.0, false));
    }

    synchronized void onDefense(ClientConnection c, double distance, boolean protect) {
        if (phase != Phase.DEFENSE || players[1 - turn] != c) return;
        onDefenseValue(distance, protect);
    }

    private synchronized void onDefenseValue(double distance, boolean protect) {
        if (phase != Phase.DEFENSE) return;
        cancelTimeout();
        if (Double.isNaN(distance)) distance = 1.0;
        resolve(engine.attack(turn, combo, Math.max(0, Math.min(1, distance)), wAttack, protect));
    }

    private synchronized void actionTimedOut() {
        if (phase != Phase.ACTION) return;
        passTurn();
    }

    /** Sin elegir a tiempo (o una acción imposible): pierde el turno, sin golpe. */
    private void passTurn() {
        resolve(engine.pass(turn));
    }

    private void resolve(ArenaEngine.Hit hit) {
        JSONObject msg = Protocol.msg("arenaHit").put("attacker", sideName(hit.attackerSide()))
                .put("w", hit.wAttack()).put("combo", hit.combo()).put("connected", hit.connected())
                .put("damage", hit.damage()).put("fainted", hit.defenderFainted()).put("replaced", hit.defenderReplaced())
                .put("guts", hit.guts()).put("big", hit.big()).put("apBonus", hit.apBonus()).put("protect", hit.protect())
                .put("state", engine.stateJson());
        send(0, msg);
        send(1, msg);
        toWatchers(msg);
        nextTurn(1 - turn);
    }

    /** Si uno se va de la sala a media pelea, gana el otro. */
    synchronized void onLeave(ClientConnection c) {
        if (phase == Phase.OVER) return;
        int side = players[0] == c ? 0 : 1;
        finish(1 - side, c.name + " salió de la sala.");
    }

    private void finish(int winnerSide, String reason) {
        if (phase == Phase.OVER) return;
        phase = Phase.OVER;
        cancelTimeout();
        JSONObject msg = Protocol.msg("arenaEnd").put("winner", players[winnerSide].id)
                .put("state", engine.stateJson());
        if (reason != null) msg.put("reason", reason);
        send(0, msg);
        send(1, msg);
        toWatchers(msg);
        battles.arenaFinished(this, players[0], players[1], players[winnerSide].id);
        VsServer.log("ARENA 2 vs 2: ganó " + players[winnerSide].name + (reason != null ? " (" + reason + ")" : ""));
    }

    /** v13: lo mismo que ven los jugadores, para quienes miran desde la pantalla de la sala. */
    private void toWatchers(JSONObject msg) {
        if (spec != null) battles.spectate().arenaMessage(spec, msg);
    }

    /**
     * v13: un espectador que llega a media pelea recibe un "arenaStart" con el estado de AHORA (los equipos y la vida
     * de cada uno); null si la partida ya terminó.
     */
    synchronized JSONObject spectatorStart() {
        if (phase == Phase.OVER) return null;
        JSONObject msg = Protocol.msg("arenaStart")
                .put("a", sideInfo(0)).put("b", sideInfo(1))
                .put("state", engine.stateJson());
        if (tournament != null) msg.put("tournament", tournament);
        return msg;
    }

    boolean involves(ClientConnection c) {
        return players[0] == c || players[1] == c;
    }

    private static String sideName(int side) {
        return side == 0 ? "a" : "b";
    }

    private void send(int side, JSONObject msg) {
        players[side].send(msg);
    }

    private void schedule(int seconds, Runnable action) {
        cancelTimeout();
        timeout = timers.schedule(action, seconds, TimeUnit.SECONDS);
    }

    private void cancelTimeout() {
        if (timeout != null) timeout.cancel(false);
        timeout = null;
    }
}
