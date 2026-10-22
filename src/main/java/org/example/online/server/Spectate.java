package org.example.online.server;

import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pantalla "COMBATES EN VIVO" de la sala (protocolo v13, conjunto 3 de la actualización conjunta): cualquiera mira
 * las peleas que están ocurriendo en la sala.
 *  - Batalla Original / Libre: el servidor la calculó entera de una vez; al espectador se le manda la MISMA pelea
 *    y la ve desde el principio (lado "a" = el que retó, a la izquierda). Queda en la lista hasta que los dos
 *    terminan de verla (battleDone) o 90 s.
 *  - ARENA 2 vs 2: se ve EN VIVO. Al entrar recibe el estado de ese momento y después los mismos turnos, golpes y
 *    cambios que los jugadores (nunca sus minijuegos: esos los manda solo el cliente de cada jugador).
 * Los mensajes al espectador van envueltos ("specBattle", "specArena") para no confundirse con sus propias peleas.
 * Quien empieza a pelear deja de mirar.
 */
final class Spectate {

    /** Un combate en la lista. */
    static final class Fight {
        final int id;
        final ClientConnection a, b;
        final String mode, tournament;
        final long startedMillis = System.currentTimeMillis();
        /** 1 vs 1: el mensaje "battle" completo. */
        volatile JSONObject battle;
        /** ARENA: la partida (para el estado al entrar) y quienes miran. */
        volatile OnlineArenaMatch arena;
        final Set<ClientConnection> watchers = ConcurrentHashMap.newKeySet();
        /** 1 vs 1: quienes ya terminaron de verla (los dos = sale de la lista). */
        final Set<ClientConnection> done = ConcurrentHashMap.newKeySet();

        Fight(int id, ClientConnection a, ClientConnection b, String mode, String tournament) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.mode = mode;
            this.tournament = tournament;
        }
    }

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, Fight> fights = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- combates

    void battleStarted(ClientConnection a, ClientConnection b, JSONObject battle, String mode, String tournament) {
        stopWatching(a);
        stopWatching(b);
        Fight f = new Fight(nextId.getAndIncrement(), a, b, mode, tournament);
        f.battle = battle;
        fights.put(f.id, f);
    }

    /** Un jugador terminó de ver su pelea 1 vs 1 (o pasaron los 90 s de seguridad). */
    void battleDone(ClientConnection c) {
        for (Fight f : fights.values()) {
            if (f.battle == null || (f.a != c && f.b != c)) continue;
            f.done.add(c);
            if (f.done.contains(f.a) && f.done.contains(f.b)) fights.remove(f.id);
        }
    }

    /** Pasados 90 s, un 1 vs 1 sale de la lista aunque alguien no avise. */
    void expire(ClientConnection a, ClientConnection b) {
        fights.values().removeIf(f -> f.battle != null && f.a == a && f.b == b
                && System.currentTimeMillis() - f.startedMillis >= 85_000);
    }

    Fight arenaStarted(OnlineArenaMatch match, ClientConnection a, ClientConnection b, String tournament) {
        stopWatching(a);
        stopWatching(b);
        Fight f = new Fight(nextId.getAndIncrement(), a, b, Protocol.MODE_ARENA, tournament);
        f.arena = match;
        fights.put(f.id, f);
        return f;
    }

    /** Lo mismo que ven los jugadores de la ARENA (turno, cambio, golpe, final), envuelto para los espectadores. */
    void arenaMessage(Fight f, JSONObject m) {
        if (f == null) return;
        String line = null;
        for (ClientConnection w : f.watchers) {
            if (line == null) line = Protocol.line(Protocol.msg("specArena").put("fight", f.id).put("m", m));
            w.sendRaw(line);
        }
        if ("arenaEnd".equals(m.optString("t"))) {
            fights.remove(f.id);
            f.watchers.clear();
        }
    }

    // ---------------------------------------------------------------- espectadores

    JSONObject list() {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        fights.values().stream().sorted((x, y) -> Integer.compare(x.id, y.id)).forEach(f -> arr.put(new JSONObject()
                .put("id", f.id).put("mode", f.mode).put("tournament", f.tournament == null ? "" : f.tournament)
                .put("a", side(f.a)).put("b", side(f.b))
                .put("seconds", (now - f.startedMillis) / 1000).put("watchers", f.watchers.size())));
        return Protocol.msg("specList").put("fights", arr);
    }

    void watch(ClientConnection c, int fightId) {
        Fight f = fights.get(fightId);
        if (f == null) {
            c.send(Protocol.msg("specEnded").put("reason", "Ese combate ya terminó."));
            return;
        }
        if (c == f.a || c == f.b || c.inBattle) {
            c.send(Protocol.msg("specEnded").put("reason", "No puedes mirar mientras peleas."));
            return;
        }
        stopWatching(c);
        if (f.battle != null) {
            // Se manda entera y se ve desde el principio; no hace falta seguirla.
            c.send(Protocol.msg("specBattle").put("fight", f.id).put("a", side(f.a)).put("b", side(f.b))
                    .put("battle", f.battle));
            return;
        }
        OnlineArenaMatch match = f.arena;
        if (match == null) return;
        JSONObject start = match.spectatorStart(); // estado de AHORA, con el mismo formato que "arenaStart"
        if (start == null) {
            c.send(Protocol.msg("specEnded").put("reason", "Ese combate ya terminó."));
            return;
        }
        c.send(Protocol.msg("specArena").put("fight", f.id).put("names", new JSONObject()
                .put("a", f.a.name).put("b", f.b.name)).put("m", start));
        f.watchers.add(c);
    }

    void stopWatching(ClientConnection c) {
        for (Fight f : fights.values()) f.watchers.remove(c);
    }

    void onLeave(ClientConnection c) {
        stopWatching(c);
    }

    private static JSONObject side(ClientConnection c) {
        JSONObject d = c.digimon;
        return new JSONObject().put("id", c.id).put("name", c.name)
                .put("species", d == null ? "Digimon" : d.optString("species", "Digimon"))
                .put("copy", VsServer.isCopy(d));
    }
}
