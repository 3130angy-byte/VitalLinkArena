package org.example.online.server;

import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TORNEOS de la sala (protocolo v12, decisiones del usuario del 2026-10-18):
 *  - los crea CUALQUIER jugador en la "Recepción de torneos": modo (Original, Libre o ARENA 2 vs 2) y cupos (4, 8 o
 *    16); hasta {@code torneo.maximoALaVez} (2) a la vez;
 *  - inscripción dinámica: cada uno pide entrar y el ORGANIZADOR lo acepta A MANO;
 *  - el organizador lo empieza cuando quiere (con 2 o más): eliminación directa, llaves al azar y "pasa directo"
 *    para completar la llave;
 *  - antes de cada combate los dos dicen "LISTO" ({@code torneo.segundosParaListo}, 60 s); quien no responde pierde
 *    ese combate (si ninguno responde, sorteo);
 *  - empate (Original y Libre): revancha, y a la TERCERA, sorteo;
 *  - quien está en un torneo no pelea retos sueltos ni cambia su equipo; salir de la sala = descalificado SOLO de ese
 *    torneo (tras un corte de conexión tiene {@code torneo.segundosParaVolver}, 60 s, para volver con su cuenta);
 *    quien queda eliminado vuelve a estar libre al instante; los que no están en un torneo pelean libres;
 *  - el campeón recibe el premio (tPrize: lucir a su Digimon al doble, lo guarda su programa).
 *
 * Todo el estado vive en UN hilo propio ({@code exec}): cada mensaje y el reloj de cada segundo se encolan ahí, así
 * no hace falta ningún candado y nunca se cruza con los de las peleas (que solo AVISAN encolando una tarea).
 * A cada jugador se lo sigue por su NOMBRE (único por cuenta en el servidor): si se corta y vuelve, sigue en su torneo.
 */
final class Tournaments {

    private enum State { REGISTRATION, RUNNING, FINISHED }

    private enum Phase { WAITING, READY, REMATCH, FIGHTING, DONE }

    /** Un inscrito. key = nombre sin mayúsculas. */
    private static final class Entry {
        final String name, key;
        String species = "Digimon";
        boolean copy;
        String look = "";
        boolean out;
        long goneSince = -1;

        Entry(String name) {
            this.name = name;
            this.key = name.toLowerCase(Locale.ROOT);
        }
    }

    private static final class Match {
        final Entry a, b; // b == null: pasa directo
        Entry winner;
        Phase phase = Phase.WAITING;
        boolean readyA, readyB;
        long askedAt;
        int fights;
        String note = "";

        Match(Entry a, Entry b) {
            this.a = a;
            this.b = b;
        }
    }

    private static final class Tournament {
        final int id;
        final String organizer, organizerKey, mode;
        final int size;
        State state = State.REGISTRATION;
        final List<Entry> players = new ArrayList<>();
        final List<Entry> pending = new ArrayList<>();
        final List<List<Match>> rounds = new ArrayList<>();
        long organizerGoneSince = -1;
        long finishedAt;
        String champion = "";

        Tournament(int id, String organizer, String mode, int size) {
            this.id = id;
            this.organizer = organizer;
            this.organizerKey = organizer.toLowerCase(Locale.ROOT);
            this.mode = mode;
            this.size = size;
        }

        String label() {
            return "Torneo de " + organizer + " (" + Protocol.modeName(mode) + ")";
        }

        List<Match> currentRound() {
            return rounds.isEmpty() ? List.of() : rounds.get(rounds.size() - 1);
        }
    }

    /** Un combate puede tardar (ARENA larga); pasado esto, se decide por sorteo para que el torneo no quede trabado. */
    private static final long FIGHT_SAFETY_MS = 20 * 60_000L;
    /** Un torneo terminado se sigue mostrando un rato (resultado y llaves) y luego se quita. */
    private static final long SHOW_FINISHED_MS = 5 * 60_000L;
    private static final int MAX_PENDING = 16;
    private static final int MAX_CHAMPIONS = 8;

    private final VsServer server;
    private final OfficialBattles battles;
    private final ServerConfig config;
    private final Random random = new Random();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vs-torneos");
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, Tournament> tournaments = new LinkedHashMap<>();
    private final Deque<JSONObject> champions = new ArrayDeque<>();
    private int nextId = 1;
    /** Nombres (sin mayúsculas) de quienes están en un torneo y siguen en carrera: se lee desde otros hilos. */
    private final Set<String> locked = ConcurrentHashMap.newKeySet();
    /** Huella del equipo con que se inscribió cada uno (para no dejar cambiarlo a mitad del torneo). */
    private final Map<String, String> lockedLook = new ConcurrentHashMap<>();

    Tournaments(VsServer server, OfficialBattles battles, ServerConfig config) {
        this.server = server;
        this.battles = battles;
        this.config = config;
        exec.scheduleAtFixedRate(() -> safely(this::tick), 1, 1, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- desde otros hilos

    /** ¿Está en un torneo y sigue en carrera? (no puede retar, ponerse disponible ni cambiar su equipo) */
    boolean isLocked(ClientConnection c) {
        return c.name != null && locked.contains(c.name.toLowerCase(Locale.ROOT));
    }

    /** ¿El equipo que manda es el mismo con que se inscribió? (prender o apagar el premio no cambia los dibujos) */
    boolean sameLook(ClientConnection c, JSONObject digimon) {
        String before = c.name == null ? null : lockedLook.get(c.name.toLowerCase(Locale.ROOT));
        return before == null || before.equals(lookKey(digimon));
    }

    /** Huella de los dibujos del puesto 1 y del compañero (sin "big": el premio se puede prender y apagar). */
    static String lookKey(JSONObject digimon) {
        if (digimon == null) return "";
        JSONObject partner = digimon.optJSONObject("partner");
        String text = String.valueOf(digimon.optJSONArray("frames")) + "|" + digimon.optInt("stage")
                + "|" + (partner == null ? "-" : partner.optJSONArray("frames") + "|" + partner.optInt("stage"));
        return Integer.toHexString(text.hashCode()) + ":" + text.length();
    }

    void handle(ClientConnection c, String type, JSONObject m) {
        exec.execute(() -> safely(() -> {
            if (c.version < Protocol.TOURNAMENT_MIN_VERSION || c.name == null || server.player(c.id) != c) return;
            switch (type) {
                case "tCreate" -> create(c, m.optString("mode"), m.optInt("size"), m.optBoolean("play", true));
                case "tJoin" -> join(c, m.optInt("id"));
                case "tAnswer" -> answer(c, m.optInt("id"), m.optString("name"), m.optBoolean("accept"));
                case "tStart" -> start(c, m.optInt("id"));
                case "tCancel" -> cancel(c, m.optInt("id"));
                case "tLeave" -> leave(c, m.optInt("id"));
                case "tReady" -> ready(c);
                default -> { }
            }
        }));
    }

    /** Entró (o volvió) a la sala: le llega el estado de los torneos. */
    void welcome(ClientConnection c) {
        exec.execute(() -> safely(() -> {
            if (c.version >= Protocol.TOURNAMENT_MIN_VERSION) c.send(update());
        }));
    }

    /** Se fue de la sala: el reloj cuenta el tiempo para volver (ver tick). */
    void onLeave(ClientConnection c) {
        exec.execute(() -> safely(this::tick));
    }

    // ---------------------------------------------------------------- acciones

    private void create(ClientConnection c, String mode, int size, boolean play) {
        String problem = null;
        if (!Protocol.isValidMode(mode)) problem = "Modo de torneo desconocido.";
        else if (java.util.Arrays.stream(Protocol.TOURNAMENT_SIZES).noneMatch(s -> s == size)) problem = "Cupos inválidos.";
        else if (involved(c.name) != null) problem = "Ya estás en un torneo (o tienes una inscripción pendiente).";
        else if (tournaments.values().stream().anyMatch(t -> t.state != State.FINISHED && t.organizerKey.equals(key(c.name)))) {
            problem = "Ya organizas un torneo.";
        } else if (activeCount() >= config.maxTournaments) {
            problem = "Ya hay " + config.maxTournaments + " torneos en marcha: espera a que termine uno.";
        } else if (play) problem = cannotPlay(c, mode);
        if (problem != null) {
            notice(c, problem, false);
            return;
        }
        Tournament t = new Tournament(nextId++, c.name, mode, size);
        tournaments.put(t.id, t);
        if (play) addPlayer(t, c);
        VsServer.log("Torneo #" + t.id + " creado por " + c.name + ": " + Protocol.modeName(mode) + ", " + size + " cupos.");
        noticeAll(c.name + " creó un torneo de " + Protocol.modeName(mode) + " (" + size + " cupos). Inscríbete en la"
                + " Recepción de torneos.");
        push();
    }

    private void join(ClientConnection c, int id) {
        Tournament t = tournaments.get(id);
        String problem = null;
        if (t == null || t.state != State.REGISTRATION) problem = "Ese torneo ya no acepta inscripciones.";
        else if (involved(c.name) != null) problem = "Ya estás en un torneo (o tienes una inscripción pendiente).";
        else if (t.players.size() >= t.size) problem = "Ese torneo ya está completo.";
        else if (t.pending.size() >= MAX_PENDING) problem = "El organizador tiene muchas solicitudes: intenta en un rato.";
        else problem = cannotPlay(c, t.mode);
        if (problem != null) {
            notice(c, problem, false);
            return;
        }
        Entry e = entryFor(c);
        t.pending.add(e);
        notice(c, "Pediste entrar al torneo de " + t.organizer + ". Espera a que te acepte.", false);
        ClientConnection organizer = server.playerNamed(t.organizer);
        if (organizer != null) notice(organizer, c.name + " quiere entrar a tu torneo: acéptalo o recházalo en la"
                + " Recepción de torneos.", true);
        push();
    }

    private void answer(ClientConnection c, int id, String name, boolean accept) {
        Tournament t = tournaments.get(id);
        if (t == null || !t.organizerKey.equals(key(c.name)) || t.state != State.REGISTRATION) return;
        Entry e = t.pending.stream().filter(p -> p.key.equals(key(name))).findFirst().orElse(null);
        if (e == null) return;
        t.pending.remove(e);
        ClientConnection who = server.playerNamed(e.name);
        if (!accept) {
            if (who != null) notice(who, t.organizer + " no te aceptó en su torneo esta vez.", false);
            push();
            return;
        }
        String problem = who == null ? e.name + " ya no está en la sala."
                : t.players.size() >= t.size ? "Tu torneo ya está completo."
                : involved(e.name) != null ? e.name + " ya está en otro torneo." : cannotPlay(who, t.mode);
        if (problem != null) {
            notice(c, problem, false);
            if (who != null && who != c) notice(who, "No pudiste entrar al torneo de " + t.organizer + ": " + problem, false);
            push();
            return;
        }
        addPlayer(t, who);
        notice(who, "¡" + t.organizer + " te aceptó en su torneo! Quédate en la sala: si sales, quedas descalificado"
                + " (solo de ese torneo).", true);
        push();
    }

    private void start(ClientConnection c, int id) {
        Tournament t = tournaments.get(id);
        if (t == null || !t.organizerKey.equals(key(c.name)) || t.state != State.REGISTRATION) return;
        if (t.players.size() < 2) {
            notice(c, "Hacen falta al menos 2 inscritos para empezar.", false);
            return;
        }
        for (Entry p : t.pending) {
            ClientConnection who = server.playerNamed(p.name);
            if (who != null) notice(who, "El torneo de " + t.organizer + " empezó sin ti (no llegaron a aceptarte).", false);
        }
        t.pending.clear();
        t.state = State.RUNNING;
        t.rounds.add(firstRound(t.players));
        VsServer.log("Torneo #" + t.id + " empezó con " + t.players.size() + " jugadores.");
        noticeAll("¡Empezó el " + t.label() + " con " + t.players.size() + " jugadores!");
        advance(t);
        push();
    }

    private void cancel(ClientConnection c, int id) {
        Tournament t = tournaments.get(id);
        if (t == null || !t.organizerKey.equals(key(c.name)) || t.state == State.FINISHED) return;
        close(t, "El organizador canceló el " + t.label() + ".");
    }

    private void leave(ClientConnection c, int id) {
        Tournament t = tournaments.get(id);
        if (t == null || t.state == State.FINISHED) return;
        String k = key(c.name);
        if (t.pending.removeIf(p -> p.key.equals(k))) {
            notice(c, "Cancelaste tu inscripción.", false);
            push();
            return;
        }
        Entry e = t.players.stream().filter(p -> p.key.equals(k)).findFirst().orElse(null);
        if (e == null || e.out) return;
        if (t.state == State.REGISTRATION) {
            t.players.remove(e);
            unlock(e);
            notice(c, "Te retiraste del torneo de " + t.organizer + " antes de que empiece.", false);
        } else {
            disqualify(t, e, "se retiró");
        }
        push();
    }

    private void ready(ClientConnection c) {
        String k = key(c.name);
        for (Tournament t : tournaments.values()) {
            if (t.state != State.RUNNING) continue;
            for (Match m : t.currentRound()) {
                if (m.phase != Phase.READY) continue;
                if (m.a.key.equals(k)) m.readyA = true;
                else if (m.b != null && m.b.key.equals(k)) m.readyB = true;
                else continue;
                advance(t);
                push();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- el reloj (cada segundo)

    private void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Tournament t : List.copyOf(tournaments.values())) {
            if (t.state == State.FINISHED) {
                if (now - t.finishedAt > SHOW_FINISHED_MS) {
                    tournaments.remove(t.id);
                    changed = true;
                }
                continue;
            }
            // El organizador se fue antes de empezar (y no volvió a tiempo): se cancela.
            if (t.state == State.REGISTRATION) {
                if (server.playerNamed(t.organizer) == null) {
                    if (t.organizerGoneSince < 0) t.organizerGoneSince = now;
                    else if (now - t.organizerGoneSince > config.reconnectSeconds * 1000L) {
                        close(t, "El organizador salió de la sala: el " + t.label() + " se canceló.");
                        continue;
                    }
                } else {
                    t.organizerGoneSince = -1;
                }
                if (t.pending.removeIf(p -> server.playerNamed(p.name) == null)) changed = true;
            }
            // Quien sigue en carrera y salió de la sala tiene un rato para volver; luego, descalificado.
            for (Entry e : List.copyOf(t.players)) {
                if (e.out) continue;
                if (server.playerNamed(e.name) != null) {
                    e.goneSince = -1;
                } else if (e.goneSince < 0) {
                    e.goneSince = now;
                } else if (now - e.goneSince > config.reconnectSeconds * 1000L) {
                    if (t.state == State.REGISTRATION) {
                        t.players.remove(e);
                        unlock(e);
                    } else {
                        disqualify(t, e, "salió de la sala");
                    }
                    changed = true;
                }
            }
            if (t.state == State.RUNNING && advance(t)) changed = true;
        }
        if (changed) push();
    }

    /**
     * Hace avanzar los combates de la ronda actual (preguntar "listo", empezar, decidir por ausencia) y, si la ronda
     * terminó, arma la siguiente o corona al campeón. true si algo cambió.
     */
    private boolean advance(Tournament t) {
        if (t.state != State.RUNNING) return false;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Match m : t.currentRound()) {
            if (m.phase == Phase.DONE || m.phase == Phase.FIGHTING) {
                if (m.phase == Phase.FIGHTING && now - m.askedAt > FIGHT_SAFETY_MS) {
                    decide(t, m, random.nextBoolean() ? m.a : m.b, "el combate no terminó: sorteo");
                    changed = true;
                }
                continue;
            }
            if (m.b == null) {
                decide(t, m, m.a, "pasa directo");
                changed = true;
                continue;
            }
            if (m.a.out || m.b.out) { // descalificado antes de pelear: gana el otro (o nadie)
                decide(t, m, m.a.out && m.b.out ? null : m.a.out ? m.b : m.a, "rival descalificado");
                changed = true;
                continue;
            }
            ClientConnection ca = server.playerNamed(m.a.name), cb = server.playerNamed(m.b.name);
            switch (m.phase) {
                case WAITING, REMATCH -> {
                    if (ca == null || cb == null || battles.isBusy(ca) || battles.isBusy(cb)) continue;
                    if (m.phase == Phase.REMATCH) { // revancha: sin volver a preguntar
                        fight(t, m, ca, cb);
                    } else {
                        m.phase = Phase.READY;
                        m.askedAt = now;
                        askReady(t, m, ca, cb);
                        askReady(t, m, cb, ca);
                    }
                    changed = true;
                }
                case READY -> {
                    if (m.readyA && m.readyB) {
                        if (ca == null || cb == null) continue; // espera a que vuelva (o a que lo descalifiquen)
                        String problem = teamChanged(m.a, ca) ? m.a.name : teamChanged(m.b, cb) ? m.b.name : null;
                        if (problem == null) problem = cannotPlay(ca, t.mode) != null ? m.a.name
                                : cannotPlay(cb, t.mode) != null ? m.b.name : null;
                        if (problem != null) {
                            Entry bad = problem.equals(m.a.name) ? m.a : m.b;
                            disqualify(t, bad, "su equipo ya no sirve para este torneo");
                        } else {
                            fight(t, m, ca, cb);
                        }
                        changed = true;
                    } else if (now - m.askedAt > config.readySeconds * 1000L) {
                        if (m.readyA != m.readyB) {
                            decide(t, m, m.readyA ? m.a : m.b, (m.readyA ? m.b : m.a).name + " no dijo LISTO a tiempo");
                        } else {
                            decide(t, m, random.nextBoolean() ? m.a : m.b, "ninguno dijo LISTO a tiempo: sorteo");
                        }
                        changed = true;
                    }
                }
                default -> { }
            }
        }
        if (t.state == State.RUNNING && t.currentRound().stream().allMatch(m -> m.phase == Phase.DONE)) {
            List<Entry> winners = new ArrayList<>();
            for (Match m : t.currentRound()) if (m.winner != null && !m.winner.out) winners.add(m.winner);
            if (winners.size() <= 1) {
                crown(t, winners.isEmpty() ? null : winners.get(0));
            } else {
                List<Match> next = new ArrayList<>();
                for (int i = 0; i < winners.size(); i += 2) {
                    next.add(new Match(winners.get(i), i + 1 < winners.size() ? winners.get(i + 1) : null));
                }
                t.rounds.add(next);
                noticePlayers(t, t.label() + ": empieza la " + roundName(t.rounds.size(), next.size()) + ".");
                advance(t);
            }
            changed = true;
        }
        return changed;
    }

    private void askReady(Tournament t, Match m, ClientConnection who, ClientConnection rival) {
        Entry r = rival == server.playerNamed(m.a.name) ? m.a : m.b;
        who.send(Protocol.msg("tReadyAsk").put("id", t.id).put("tournament", t.label())
                .put("round", roundName(t.rounds.size(), t.currentRound().size()))
                .put("opponent", rival.name).put("species", r.species).put("copy", r.copy)
                .put("seconds", config.readySeconds));
    }

    private void fight(Tournament t, Match m, ClientConnection ca, ClientConnection cb) {
        m.phase = Phase.FIGHTING;
        m.askedAt = System.currentTimeMillis();
        m.fights++;
        String label = t.label() + (m.fights > 1 ? " — revancha " + m.fights + " de 3" : "");
        int idA = ca.id;
        battles.startTournamentFight(ca, cb, t.mode, label,
                winnerId -> exec.execute(() -> safely(() -> fightEnded(t, m, winnerId == 0 ? 0 : winnerId == idA ? 1 : 2))));
    }

    /** result: 1 = ganó a, 2 = ganó b, 0 = empate. */
    private void fightEnded(Tournament t, Match m, int result) {
        if (m.phase != Phase.FIGHTING || t.state != State.RUNNING) return;
        if (result == 0) {
            if (m.fights >= 3) {
                decide(t, m, random.nextBoolean() ? m.a : m.b, "tres empates: sorteo");
            } else {
                m.phase = Phase.REMATCH;
                m.note = "empate: revancha";
                noticeTo(m, "¡Empate! Revancha (" + (m.fights + 1) + " de 3; al tercer empate, sorteo).");
            }
        } else {
            decide(t, m, result == 1 ? m.a : m.b, "");
        }
        advance(t);
        push();
    }

    private void decide(Tournament t, Match m, Entry winner, String note) {
        m.phase = Phase.DONE;
        m.winner = winner;
        m.note = note;
        for (Entry e : new Entry[]{m.a, m.b}) {
            if (e == null || e == winner || e.out) continue;
            e.out = true;
            unlock(e);
            ClientConnection c = server.playerNamed(e.name);
            if (c != null) notice(c, "Perdiste tu combate y sales del " + t.label() + (note.isEmpty() ? "" : " (" + note + ")")
                    + ". Ya puedes pelear libre o salir de la sala.", false);
        }
        if (winner != null && m.b != null) {
            ClientConnection c = server.playerNamed(winner.name);
            if (c != null && t.currentRound().size() > 1) {
                notice(c, "¡Ganaste tu combate" + (note.isEmpty() ? "" : " (" + note + ")") + "! Espera en la sala la"
                        + " siguiente ronda.", false);
            }
        }
    }

    private void disqualify(Tournament t, Entry e, String why) {
        if (e.out) return;
        e.out = true;
        unlock(e);
        VsServer.log("Torneo #" + t.id + ": " + e.name + " descalificado (" + why + ").");
        ClientConnection c = server.playerNamed(e.name);
        if (c != null) notice(c, "Quedas fuera del " + t.label() + " por descalificación (" + why + ").", false);
        noticePlayers(t, e.name + " queda fuera del torneo por descalificación (" + why + ").");
        advance(t);
    }

    private void crown(Tournament t, Entry champion) {
        t.state = State.FINISHED;
        t.finishedAt = System.currentTimeMillis();
        for (Entry e : t.players) unlock(e);
        if (champion == null) {
            noticeAll("El " + t.label() + " terminó sin campeón (todos quedaron fuera).");
            return;
        }
        t.champion = champion.name;
        champions.addFirst(new JSONObject().put("name", champion.name).put("species", champion.species)
                .put("mode", t.mode).put("players", t.players.size()).put("when", t.finishedAt));
        while (champions.size() > MAX_CHAMPIONS) champions.removeLast();
        VsServer.log("Torneo #" + t.id + ": campeón " + champion.name + " (" + champion.species + ").");
        noticeAll("¡" + champion.name + " y su " + champion.species + " son los CAMPEONES del " + t.label() + "!");
        ClientConnection c = server.playerNamed(champion.name);
        if (c != null) {
            c.send(Protocol.msg("tPrize").put("id", t.id).put("mode", t.mode).put("tournament", t.label()));
        }
    }

    private void close(Tournament t, String reason) {
        for (Entry e : t.players) unlock(e);
        List<String> notify = new ArrayList<>();
        t.players.forEach(e -> notify.add(e.name));
        t.pending.forEach(e -> notify.add(e.name));
        tournaments.remove(t.id);
        VsServer.log("Torneo #" + t.id + " cerrado: " + reason);
        for (String n : notify) {
            ClientConnection c = server.playerNamed(n);
            if (c != null) notice(c, reason, false);
        }
        push();
    }

    // ---------------------------------------------------------------- auxiliares

    private void addPlayer(Tournament t, ClientConnection c) {
        Entry e = entryFor(c);
        t.players.add(e);
        locked.add(e.key);
        lockedLook.put(e.key, e.look);
        // Inscrito = ya no se le puede retar ni ponerse disponible.
        if (c.available) {
            c.available = false;
            c.send(Protocol.msg("availability").put("on", false).put("reason", OfficialBattles.TOURNAMENT_LOCKED));
        }
    }

    private Entry entryFor(ClientConnection c) {
        Entry e = new Entry(c.name);
        JSONObject d = c.digimon;
        if (d != null) {
            e.species = d.optString("species", "Digimon");
            e.copy = VsServer.isCopy(d);
        }
        e.look = c.lookKey;
        return e;
    }

    private void unlock(Entry e) {
        locked.remove(e.key);
        lockedLook.remove(e.key);
    }

    private boolean teamChanged(Entry e, ClientConnection c) {
        return !e.look.isEmpty() && !e.look.equals(c.lookKey);
    }

    /** Por qué no puede jugar ese modo (null = sí puede). */
    private static String cannotPlay(ClientConnection c, String mode) {
        if (c.digimon == null || c.stats == null) return "Necesitas traer a tu Digimon para jugar un torneo.";
        if (c.stage < Protocol.MIN_BATTLE_STAGE) return "En los torneos solo pelean Digimon Child o superiores.";
        if (Protocol.MODE_ARENA.equals(mode)) return OnlineArenaMatch.teamProblem(c);
        return null;
    }

    /** El torneo (no terminado) en que está o pidió entrar, o null. */
    private Tournament involved(String name) {
        String k = key(name);
        for (Tournament t : tournaments.values()) {
            if (t.state == State.FINISHED) continue;
            if (t.pending.stream().anyMatch(p -> p.key.equals(k))) return t;
            if (t.players.stream().anyMatch(p -> p.key.equals(k) && !p.out)) return t;
        }
        return null;
    }

    private long activeCount() {
        return tournaments.values().stream().filter(t -> t.state != State.FINISHED).count();
    }

    private static List<Match> firstRound(List<Entry> players) {
        List<Entry> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        int slots = 2;
        while (slots < shuffled.size()) slots *= 2;
        int byes = slots - shuffled.size();
        List<Match> round = new ArrayList<>();
        int i = 0;
        for (int b = 0; b < byes; b++) round.add(new Match(shuffled.get(i++), null));
        while (i + 1 < shuffled.size()) {
            round.add(new Match(shuffled.get(i), shuffled.get(i + 1)));
            i += 2;
        }
        Collections.shuffle(round);
        return round;
    }

    private static String roundName(int number, int matches) {
        if (matches == 1) return "FINAL";
        if (matches == 2) return "semifinal";
        if (matches == 4) return "ronda de cuartos";
        return "ronda " + number;
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- mensajes

    private JSONObject update() {
        JSONArray list = new JSONArray();
        for (Tournament t : tournaments.values()) {
            JSONArray players = new JSONArray();
            for (Entry e : t.players) {
                players.put(new JSONObject().put("name", e.name).put("species", e.species).put("copy", e.copy)
                        .put("out", e.out).put("away", e.goneSince >= 0));
            }
            JSONArray pending = new JSONArray();
            for (Entry e : t.pending) pending.put(new JSONObject().put("name", e.name).put("species", e.species).put("copy", e.copy));
            JSONArray rounds = new JSONArray();
            for (int r = 0; r < t.rounds.size(); r++) {
                JSONArray matches = new JSONArray();
                for (Match m : t.rounds.get(r)) {
                    matches.put(new JSONObject().put("a", m.a.name).put("b", m.b == null ? "" : m.b.name)
                            .put("winner", m.winner == null ? "" : m.winner.name)
                            .put("state", m.phase.name().toLowerCase(Locale.ROOT)).put("note", m.note));
                }
                rounds.put(new JSONObject().put("name", roundName(r + 1, t.rounds.get(r).size())).put("matches", matches));
            }
            list.put(new JSONObject().put("id", t.id).put("organizer", t.organizer).put("mode", t.mode)
                    .put("size", t.size).put("label", t.label())
                    .put("state", switch (t.state) {
                        case REGISTRATION -> "inscripcion";
                        case RUNNING -> "en curso";
                        case FINISHED -> "terminado";
                    })
                    .put("players", players).put("pending", pending).put("rounds", rounds).put("champion", t.champion));
        }
        return Protocol.msg("tUpdate").put("max", config.maxTournaments).put("tournaments", list)
                .put("champions", new JSONArray(champions));
    }

    /** El estado nuevo a todos los clientes v12 (cabe de sobra en una línea: ~2 KB por torneo de 16). */
    private void push() {
        server.broadcastV12(update());
    }

    private static void notice(ClientConnection c, String text, boolean sound) {
        if (c.version >= Protocol.TOURNAMENT_MIN_VERSION) c.send(Protocol.msg("tNotice").put("text", text).put("sound", sound));
    }

    private void noticeAll(String text) {
        server.broadcastV12(Protocol.msg("tNotice").put("text", text).put("sound", false));
    }

    private void noticePlayers(Tournament t, String text) {
        for (Entry e : t.players) {
            ClientConnection c = server.playerNamed(e.name);
            if (c != null) notice(c, text, false);
        }
    }

    private void noticeTo(Match m, String text) {
        for (Entry e : new Entry[]{m.a, m.b}) {
            if (e == null) continue;
            ClientConnection c = server.playerNamed(e.name);
            if (c != null) notice(c, text, false);
        }
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            VsServer.log("Error en los torneos: " + e);
        }
    }
}
