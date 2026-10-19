package org.example.online.server;

import org.example.online.AccessList;
import org.example.online.LobbyMap;
import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Una conexión de jugador en el servidor. Lee sus mensajes en su propio
 * hilo y los valida TODOS: nada de lo que manda el cliente se usa sin
 * revisar (documento de diseño, "Integridad y seguridad").
 *
 * Protecciones:
 *  - tope de tamaño por línea (Protocol.readLine) y de mensajes por segundo
 *    (RateLimiter) -- nadie inunda el chat ni el servidor;
 *  - desconexión por inactividad (Protocol.IDLE_TIMEOUT_SECONDS) -- sin
 *    conexiones zombis ni ataques de conexiones lentas;
 *  - cola de salida con tope y su propio hilo -- un jugador que deja de
 *    leer no frena a los demás; si la llena, se le desconecta;
 *  - el Digimon llega como píxeles crudos con tamaños validados, nunca
 *    como archivo de imagen, y el rango lo calcula el servidor.
 */
class ClientConnection implements Runnable {

    private static final int OUTBOX_CAPACITY = 256;
    private static final String CLOSE_SIGNAL = "\u0000close";

    private final VsServer server;
    private final Socket socket;
    private final Writer out;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(OUTBOX_CAPACITY);
    private final RateLimiter chatLimiter;
    private final RateLimiter moveLimiter;
    /** Un reto cada 3 s como máximo: evita bombardear a otros con solicitudes. */
    private final RateLimiter challengeLimiter = new RateLimiter(1.0 / 3, 1);
    /** Cambiar el equipo desde la PC: uno cada 5 s como máximo. */
    private final RateLimiter teamLimiter = new RateLimiter(1.0 / 5, 2);
    private volatile boolean closed = false;

    // Estado del jugador. Lo escribe este hilo (camino) y el tick (posición).
    volatile int id = -1;
    volatile String name;
    volatile double x, y;
    /** Camino por casillas hacia el último destino pedido; el tick lo recorre. */
    volatile List<double[]> path = List.of();
    volatile int pathIndex = 0;
    /** Solo para camino+índice; separado del de envío para que un socket lento no frene el tick. */
    final Object moveLock = new Object();
    /** Mensaje "digimon" ya validado y listo para repartir (null hasta que lo mande). */
    volatile JSONObject digimon;
    /** Huella de los dibujos del equipo (sin el premio): en un torneo el equipo no puede cambiar. */
    volatile String lookKey = "";
    /** Stats del Digimon (dp, hp, ap, small, big, activity) + etapa y Power Trophies: SOLO el servidor los ve. */
    volatile int[] stats;
    volatile int stage;
    volatile int powerTrophies;
    /** Compañero (puesto 2) para el 2 vs 2: mismo formato; null si no trajo. */
    volatile int[] partnerStats;
    volatile int partnerStage;
    volatile int partnerPowerTrophies;
    volatile int partnerAttribute;
    /** Cuenta de quien se conectó (TailscaleIdentity): la clave y cómo se muestra. */
    volatile String identity = "";
    volatile String identityLabel = "";
    /** Nombre con el que pidió permiso mientras espera la respuesta del anfitrión (null = no espera). */
    volatile String requestedName;
    volatile long waitingSince;
    /** Batallas Oficiales: disponible para que lo reten, y si está peleando ahora. */
    volatile boolean available;
    volatile boolean inBattle;
    /** Versión del protocolo del cliente (del "hello"): los torneos piden la v12. */
    volatile int version;
    /** Torneos: "listo" por combate y crear/inscribirse, con tope para que nadie inunde al organizador. */
    private final RateLimiter tournamentLimiter = new RateLimiter(2, 6);

    ClientConnection(VsServer server, Socket socket, ServerConfig config) throws IOException {
        this.server = server;
        this.socket = socket;
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.chatLimiter = new RateLimiter(config.chatPerSecond, config.chatBurst);
        this.moveLimiter = new RateLimiter(config.movesPerSecond, config.movesPerSecond);
        // Un desconocido tiene pocos segundos para presentarse (hello); después, la inactividad normal.
        socket.setSoTimeout(HELLO_TIMEOUT_SECONDS * 1000);
    }

    /** Segundos para mandar el "hello" antes de que se corte la conexión. */
    static final int HELLO_TIMEOUT_SECONDS = 10;

    String remoteIp() {
        return socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        Thread writer = new Thread(this::writeLoop, "vs-writer-" + remoteIp());
        writer.setDaemon(true);
        writer.start();
        // Sin try-with-resources a propósito: cerrar este Reader cerraría TODO el socket
        // antes de que el hilo de escritura mande el último aviso (p. ej. el motivo de un rechazo).
        try {
            Reader in = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
            String line;
            while ((line = Protocol.readLine(in)) != null) {
                if (!handle(line)) break;
            }
        } catch (IOException e) {
            // Conexión cortada, inactividad o línea abusiva: se trata igual, el jugador sale.
        } finally {
            server.leave(this);
            // Primero sale lo que ya estaba en cola (por ejemplo, el "error" con el motivo
            // del rechazo); si el cliente no lee, se corta igual a los 2 s.
            closeGracefully();
            Thread hardClose = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                close();
            }, "vs-hard-close");
            hardClose.setDaemon(true);
            hardClose.start();
        }
    }

    /** Devuelve false si hay que cerrar la conexión. */
    private boolean handle(String line) {
        JSONObject m;
        try {
            m = new JSONObject(line);
        } catch (JSONException e) {
            return fail("Mensaje inválido.");
        }
        String type = m.optString("t", "");

        if (id <= 0) {
            // Esperando el permiso del anfitrión: solo se acepta el ping (el servidor lo deja entrar o lo despide).
            if (requestedName != null) return true;
            // Antes del "hello" no se acepta nada más.
            if (!type.equals("hello")) return fail("El primer mensaje debe ser hello.");
            int v = m.optInt("v", -1);
            if (v < Protocol.MIN_COMPATIBLE_VERSION) {
                return fail("Tu versión del juego es muy vieja para este servidor: baja la última.");
            }
            if (v > Protocol.VERSION) {
                return fail("El servidor del anfitrión es más viejo que tu juego: pídele que lo actualice.");
            }
            version = v;
            String cleanName = Protocol.cleanText(m.optString("name"), Protocol.MAX_NAME_LENGTH);
            try {
                socket.setSoTimeout(Protocol.IDLE_TIMEOUT_SECONDS * 1000); // ya se presentó: inactividad normal
            } catch (java.net.SocketException ignored) {
            }
            // Capa 1: la cuenta de Tailscale desde la que llega (el nombre se puede inventar; la cuenta no).
            TailscaleIdentity.Identity who = TailscaleIdentity.of(socket.getInetAddress());
            identity = who.key();
            identityLabel = who.label();
            AccessList list = AccessList.load();
            // Cuenta ya aceptada: entra con SU nombre registrado aunque escriba otro o ninguno
            // (desde el celular con la misma cuenta, sin tener que escribirlo exacto).
            java.util.Optional<String> registered = list.nameFor(identity);
            if (registered.isPresent()) {
                if (!registered.get().equalsIgnoreCase(cleanName)) {
                    VsServer.log(identityLabel + " escribió \"" + cleanName + "\": entra con su nombre registrado, " + registered.get());
                }
                return enter(registered.get());
            }
            if (cleanName.isEmpty()) return fail("Falta el nombre.");
            if (list.isAllowed(cleanName, identity)) return enter(cleanName); // desde esta PC
            // Capa 2: sin permiso, PIDE PERMISO y espera a que el anfitrión responda.
            AccessList.Asked asked = AccessList.ask(cleanName, identity, identityLabel, remoteIp());
            if (asked == AccessList.Asked.FULL) {
                server.noteRejected(remoteIp());
                send(Protocol.msg("error").put("code", "notAllowed").put("msg", "El anfitrión tiene muchas solicitudes"
                        + " sin responder: vuelve a intentarlo más tarde. Las funciones locales siguen disponibles."));
                return false;
            }
            // Una solicitud NUEVA cuenta como rechazo: cambiar de nombre una y otra vez termina bloqueando la IP.
            if (asked == AccessList.Asked.NEW) server.noteRejected(remoteIp());
            VsServer.log("Solicitud de acceso: " + cleanName + " (" + identityLabel + ") desde " + remoteIp());
            requestedName = cleanName;
            waitingSince = System.currentTimeMillis();
            String host = list.host().isEmpty() ? "el anfitrión" : list.host();
            send(Protocol.msg("accessPending").put("host", host).put("seconds", VsServer.WAIT_FOR_PERMISSION_SECONDS)
                    .put("msg", "Le pediste permiso a " + host + " para entrar a su sala. Espera a que te acepte..."));
            server.waitForPermission(this);
            return true;
        }

        switch (type) {
            case "move" -> {
                if (!moveLimiter.tryAcquire()) return true; // demasiados clics: se ignora este
                // El destino se recorta a la sala; el camino (alrededor de los muros)
                // y la velocidad los pone el servidor.
                double tx = clamp(m.optDouble("x", x), 0, Protocol.ROOM_WIDTH - 1);
                double ty = clamp(m.optDouble("y", y), 0, Protocol.ROOM_HEIGHT - 1);
                List<double[]> newPath = LobbyMap.findPath(x, y, tx, ty);
                synchronized (moveLock) { // mismo candado que el tick: camino e índice cambian juntos
                    pathIndex = 0;
                    path = newPath;
                }
            }
            case "chat" -> {
                if (!chatLimiter.tryAcquire()) {
                    send(Protocol.msg("error").put("msg", "Vas muy rápido: espera un momento para volver a escribir."));
                    return true;
                }
                String text = Protocol.cleanText(m.optString("text"), Protocol.MAX_CHAT_LENGTH);
                if (!text.isEmpty()) server.chat(this, text);
            }
            case "digimon" -> {
                // Se puede volver a mandar (cambio de equipo en la PC), pero nunca en medio de un reto o pelea.
                if (digimon != null) {
                    if (server.battles().isBusy(this)) {
                        send(Protocol.msg("teamRejected").put("reason", "No puedes cambiar tu equipo durante un reto o una pelea."));
                        return true;
                    }
                    if (!teamLimiter.tryAcquire()) {
                        send(Protocol.msg("teamRejected").put("reason", "Espera un momento antes de volver a cambiar tu equipo."));
                        return true;
                    }
                }
                // En un torneo, el equipo es el de la inscripción (también al volver tras un corte); el premio sí cambia.
                if (server.tournaments().isLocked(this) && !server.tournaments().sameLook(this, m)) {
                    send(Protocol.msg("teamRejected").put("reason", "Estás en un torneo: tu equipo no puede cambiar"
                            + " hasta que termine (o te retires en la Recepción de torneos)."));
                    return true;
                }
                String problem = validateDigimon(m);
                if (problem == null && m.has("partner")) {
                    JSONObject partner = m.optJSONObject("partner");
                    problem = partner == null ? "compañero inválido." : validateDigimon(partner);
                    if (problem != null) problem = "compañero: " + problem;
                }
                if (problem != null) return fail("Digimon rechazado: " + problem);
                server.digimonArrived(this, m);
            }
            case "available" -> server.battles().setAvailable(this, m.optBoolean("on", false));
            case "listAvailable" -> server.battles().sendAvailableList(this);
            case "challenge" -> {
                if (!challengeLimiter.tryAcquire()) {
                    send(Protocol.msg("challengeEnded").put("reason", "Espera un momento antes de volver a retar."));
                    return true;
                }
                server.battles().challenge(this, m.optInt("target", -1), m.optString("mode", ""));
            }
            case "challengeReply" -> server.battles().reply(this, m.optInt("from", -1), m.optBoolean("accept", false));
            case "battleDone" -> server.battles().battleDone(this);
            // ARENA 2 vs 2 online: la partida ignora todo lo que no corresponda a su turno y fase.
            case "arenaAction" -> server.battles().arenaAction(this, m.optString("action", "attack"));
            case "arenaCombo" -> server.battles().arenaCombo(this, m.optInt("combo", 0));
            case "arenaDefense" -> server.battles().arenaDefense(this, m.optDouble("distance", 1.0), m.optBoolean("protect", false));
            // Torneos (v12): el servidor revisa quién puede hacer cada cosa (Tournaments).
            case "tCreate", "tJoin", "tAnswer", "tStart", "tCancel", "tLeave", "tReady" -> {
                if (!tournamentLimiter.tryAcquire()) return true;
                server.tournaments().handle(this, type, m);
            }
            case "ping" -> { /* basta con haber llegado: reinicia la espera de inactividad */ }
            default -> { /* tipo desconocido: se ignora, sin cortar (compatibilidad futura) */ }
        }
        return true;
    }

    /** null si todo está bien; si no, el motivo. Revisa CADA campo antes de repartirlo a otros jugadores. */
    private static String validateDigimon(JSONObject m) {
        int attribute = m.optInt("attribute", -1);
        int stage = m.optInt("stage", -1);
        int trophies = m.optInt("powerTrophies", -1);
        if (attribute < 0 || attribute > 4) return "atributo fuera de rango.";
        if (stage < 0 || stage > 5) return "etapa fuera de rango.";
        if (trophies < 0 || trophies > Protocol.MAX_POWER_TROPHIES) return "Power Trophies fuera de rango.";
        JSONArray frames = m.optJSONArray("frames");
        if (frames == null || frames.length() != Protocol.DIGIMON_FRAMES) return "cantidad de cuadros incorrecta.";
        for (int i = 0; i < frames.length(); i++) {
            String problem = validatePixels(frames.optJSONObject(i), Protocol.MAX_FRAME_WIDTH, Protocol.MAX_FRAME_HEIGHT);
            if (problem != null) return "cuadro " + i + " " + problem;
        }
        String nameProblem = validatePixels(m.optJSONObject("nameSprite"),
                Protocol.MAX_NAME_SPRITE_WIDTH, Protocol.MAX_NAME_SPRITE_HEIGHT);
        if (nameProblem != null) return "sprite del nombre " + nameProblem;
        JSONObject s = m.optJSONObject("stats");
        if (s == null) return "faltan los stats.";
        if (!inRange(s, "dp", 0, 9999) || !inRange(s, "hp", 1, 9999) || !inRange(s, "ap", 0, 9999)
                || !inRange(s, "small", 0, 65535) || !inRange(s, "big", 0, 65535) || !inRange(s, "activity", 0, 4)) {
            return "stats fuera de rango.";
        }
        return null;
    }

    private static String validatePixels(JSONObject f, int maxW, int maxH) {
        if (f == null) return "inválido.";
        int w = f.optInt("w", -1), h = f.optInt("h", -1);
        if (w < 1 || h < 1 || w > maxW || h > maxH) return "de tamaño inválido.";
        byte[] px;
        try {
            px = Base64.getDecoder().decode(f.optString("px", ""));
        } catch (IllegalArgumentException e) {
            return "mal codificado.";
        }
        return px.length == w * h * 2 ? null : "no coincide con su tamaño.";
    }

    private static boolean inRange(JSONObject o, String key, int min, int max) {
        if (!o.has(key)) return false;
        int v = o.optInt(key, Integer.MIN_VALUE);
        return v >= min && v <= max;
    }

    /** Entra a la sala. Devuelve false (y avisa) si está llena. */
    boolean enter(String cleanName) {
        requestedName = null;
        if (server.join(this, cleanName) == VsServer.ROOM_FULL) return fail("La sala está llena.");
        return true;
    }

    private boolean fail(String reason) {
        send(Protocol.msg("error").put("msg", reason));
        return false;
    }

    void send(JSONObject message) {
        sendRaw(Protocol.line(message));
    }

    /** Nunca bloquea: deja la línea en la cola. Si la cola está llena, este jugador no está leyendo -> fuera. */
    void sendRaw(String line) {
        if (closed) return;
        if (!outbox.offer(line)) {
            VsServer.log((name != null ? name : remoteIp()) + " no lee sus mensajes (cola llena): se desconecta.");
            close();
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                String line = outbox.take();
                if (line == CLOSE_SIGNAL) break;
                out.write(line);
                if (outbox.isEmpty()) out.flush();
            }
            out.flush();
        } catch (IOException | InterruptedException e) {
            // socket cerrado: el hilo lector se encarga de sacar al jugador
        } finally {
            close();
        }
    }

    /** Cierra la conexión de forma ordenada (los mensajes ya en cola intentan salir antes). */
    void closeGracefully() {
        outbox.offer(CLOSE_SIGNAL);
    }

    void close() {
        if (closed) return;
        closed = true;
        outbox.clear();
        outbox.offer(CLOSE_SIGNAL);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }
}
