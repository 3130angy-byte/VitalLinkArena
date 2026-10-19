package org.example.online.server;

import org.example.online.AccessList;
import org.example.online.LobbyMap;
import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor del VS Online -- Fase 1 (esqueleto de red) + mapa del 1er piso
 * (LobbyMap: muros y caminos alrededor de ellos). Una sala, posiciones
 * y chat. Corre en la PC del usuario, sin nube.
 *
 * El servidor MUEVE a los jugadores: cada cliente solo manda su destino, el
 * servidor calcula el camino por casillas (LobbyMap.findPath) y
 * lo recorre a velocidad fija en cada tick (20 por segundo), sin atravesar
 * muros. Así nadie puede teletransportarse ni atravesar paredes mandando
 * coordenadas falsas, y todos ven exactamente lo mismo.
 *
 * Uso: .\gradlew.bat runServer            (puerto 7777)
 *      .\gradlew.bat runServer --args=8000
 */
public class VsServer {

    private static final int TICKS_PER_SECOND = 20;
    private static final int SNAPSHOT_EVERY_TICKS = 2;       // 10 fotos por segundo
    private static final double SPEED_UNITS_PER_SECOND = Protocol.WALK_SPEED;
    private static final String ROOM_NAME = "sala-1";

    static final int ROOM_FULL = -1;

    private final int port;
    private final ServerConfig config = ServerConfig.load();
    private final OfficialBattles battles = new OfficialBattles(this, config);
    private final Tournaments tournaments = new Tournaments(this, battles, config);
    private final Map<Integer, ClientConnection> clients = new ConcurrentHashMap<>();
    /** Todas las conexiones abiertas (también las que aún no mandaron hello), para el límite por IP y el apagado. */
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vs-tick");
        t.setDaemon(true);
        return t;
    });
    private long tickCount = 0;
    private volatile ServerSocket serverSocket;
    private volatile boolean shuttingDown = false;

    /**
     * Embebido = corre DENTRO del programa del V-Pet (lo arranca la sala si no
     * encuentra servidor en esta PC): sin consola de comandos, y "apagar" no
     * cierra el programa entero.
     */
    private final boolean embedded;
    private final CountDownLatch listening = new CountDownLatch(1);
    private static VsServer embeddedInstance;

    public VsServer(int port) {
        this(port, false);
    }

    private VsServer(int port, boolean embedded) {
        this.port = port;
        this.embedded = embedded;
    }

    /**
     * Arranca (una sola vez) un servidor dentro de este programa, en segundo
     * plano. Devuelve true si quedó escuchando; false si el puerto está
     * ocupado por otra cosa o no pudo arrancar en 3 s.
     */
    public static synchronized boolean startEmbedded(int port) {
        if (embeddedInstance != null) return embeddedInstance.listening.getCount() == 0;
        VsServer server = new VsServer(port, true);
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (IOException e) {
                log("No se pudo iniciar el servidor local: " + e.getMessage());
            }
        }, "vs-server-embebido");
        t.setDaemon(true);
        t.start();
        try {
            if (!server.listening.await(3, TimeUnit.SECONDS)) return false;
        } catch (InterruptedException e) {
            return false;
        }
        embeddedInstance = server;
        return true;
    }

    /** La IP de Tailscale de esta PC (la que el anfitrión les pasa a sus amigos), o null sin Tailscale conectado. */
    public static String tailscaleIp() {
        return TailscaleIdentity.ownIpv4();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        new VsServer(port).run();
    }

    public void run() throws IOException {
        ticker.scheduleAtFixedRate(this::tick, 0, 1000 / TICKS_PER_SECOND, TimeUnit.MILLISECONDS);
        // Lista de acceso: si el anfitrión retira a alguien, sale de la sala (se revisa cada 2 s).
        AccessList.writeOnline(List.of());
        ticker.scheduleAtFixedRate(this::checkAccessList, 2, 2, TimeUnit.SECONDS);
        if (!embedded) startConsole();
        try (ServerSocket ss = new ServerSocket(port)) {
            serverSocket = ss;
            listening.countDown();
            log("Servidor VS Online escuchando en el puerto " + port + " (" + ROOM_NAME + ", máx. "
                    + Protocol.MAX_PLAYERS_PER_ROOM + " jugadores).");
            if (embedded) log("(Servidor local dentro del programa del V-Pet: se cierra junto con él.)");
            else log("Escribe 'ayuda' para ver los comandos. 'apagar' cierra el servidor avisando a todos.");
            String tailscale = tailscaleIp();
            log(tailscale != null ? "Tu IP de Tailscale: " + tailscale + " (tus amigos la escriben en \"Servidor\")."
                    : "No encontré Tailscale conectado en esta PC: sin él, nadie de afuera llega (pasos en el LEEME).");
            while (!shuttingDown) {
                Socket socket;
                try {
                    socket = ss.accept();
                } catch (IOException e) {
                    if (shuttingDown) break; // el apagado cerró el socket a propósito
                    throw e;
                }
                String ip = socket.getInetAddress().getHostAddress();
                if (isBlocked(ip)) { // muchos nombres rechazados seguidos: bloqueada un rato
                    socket.close();
                    continue;
                }
                long fromSameIp = connections.stream().filter(c -> c.remoteIp().equals(ip)).count();
                if (fromSameIp >= config.maxConnectionsPerIp) {
                    log("Rechazada conexión de " + ip + ": ya tiene " + fromSameIp + " abiertas.");
                    socket.close();
                    continue;
                }
                socket.setTcpNoDelay(true);
                ClientConnection connection = new ClientConnection(this, socket, config);
                connections.add(connection);
                Thread t = new Thread(() -> {
                    try {
                        connection.run();
                    } finally {
                        connections.remove(connection);
                    }
                }, "vs-client-" + socket.getRemoteSocketAddress());
                t.setDaemon(true);
                t.start();
            }
        }
        log("Servidor apagado.");
    }

    // ---------------------------------------------------------------- consola / emergencia

    /**
     * Comandos escritos en la consola del servidor. 'apagar' es el botón de
     * emergencia: avisa a todos, cierra cada conexión y termina el proceso.
     */
    private void startConsole() {
        Thread console = new Thread(() -> {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+", 2);
                    switch (parts[0].toLowerCase()) {
                        case "apagar", "stop", "salir" -> shutdown("El servidor se cerró.");
                        case "jugadores" -> {
                            log("Jugadores: " + clients.size() + " | conexiones abiertas: " + connections.size());
                            clients.values().forEach(c -> log("  #" + c.id + " " + c.name + " (" + c.remoteIp() + ")"));
                        }
                        case "expulsar" -> kick(parts.length > 1 ? parts[1] : "");
                        case "solicitudes" -> listRequests();
                        case "aceptar" -> answerRequest(parts.length > 1 ? parts[1] : "", true);
                        case "rechazar" -> answerRequest(parts.length > 1 ? parts[1] : "", false);
                        case "ayuda" -> log("Comandos: jugadores | expulsar <número> | solicitudes | aceptar <número>"
                                + " | rechazar <número> | apagar");
                        case "" -> { }
                        default -> log("Comando desconocido. Escribe 'ayuda'.");
                    }
                }
            } catch (IOException ignored) {
                // sin consola (por ejemplo, lanzado sin entrada): el servidor sigue funcionando
            }
        }, "vs-console");
        console.setDaemon(true);
        console.start();
    }

    private void kick(String idText) {
        try {
            ClientConnection c = clients.get(Integer.parseInt(idText.replace("#", "").trim()));
            if (c == null) {
                log("No hay ningún jugador con ese número.");
                return;
            }
            c.send(Protocol.msg("error").put("msg", "Fuiste expulsado de la sala."));
            c.closeGracefully();
            log("Expulsado: " + c.name + " (#" + c.id + ")");
        } catch (NumberFormatException e) {
            log("Uso: expulsar <número>  (el número sale en 'jugadores')");
        }
    }

    /** Consola: las solicitudes de acceso pendientes, numeradas desde 1. */
    private void listRequests() {
        List<AccessList.Request> pending = AccessList.requests();
        if (pending.isEmpty()) {
            log("No hay solicitudes de acceso.");
            return;
        }
        for (int i = 0; i < pending.size(); i++) {
            AccessList.Request r = pending.get(i);
            log("  " + (i + 1) + ") " + r.name() + " -- " + r.label() + (r.note().isEmpty() ? "" : "  " + r.note()));
        }
        log("Escribe 'aceptar <número>' o 'rechazar <número>'.");
    }

    private void answerRequest(String numberText, boolean accept) {
        List<AccessList.Request> pending = AccessList.requests();
        int n;
        try {
            n = Integer.parseInt(numberText.trim());
        } catch (NumberFormatException e) {
            n = -1;
        }
        if (n < 1 || n > pending.size()) {
            log("Uso: " + (accept ? "aceptar" : "rechazar") + " <número>  (el número sale en 'solicitudes')");
            return;
        }
        AccessList.Request r = pending.get(n - 1);
        if (accept) {
            String error = AccessList.approve(r.id());
            log(error != null ? error : "Aceptado: " + r.name() + " (" + r.label() + ")");
        } else {
            AccessList.reject(r.id());
            log("Rechazado: " + r.name());
        }
    }

    // ---------------------------------------------------------------- protecciones

    /** Rechazos por IP (lista de acceso): 5 en 60 s = esa IP bloqueada 10 minutos (luego vuelve a poder). */
    private static final int MAX_REJECTIONS = 5;
    private static final long REJECTION_WINDOW_MS = 60_000, BLOCK_MS = 10 * 60_000;
    private final Map<String, java.util.ArrayDeque<Long>> rejections = new ConcurrentHashMap<>();
    private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();

    void noteRejected(String ip) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> times = rejections.computeIfAbsent(ip, k -> new java.util.ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > REJECTION_WINDOW_MS) times.pollFirst();
            if (times.size() >= MAX_REJECTIONS) {
                blockedUntil.put(ip, now + BLOCK_MS);
                times.clear();
                log("IP " + ip + " bloqueada 10 minutos: demasiados nombres rechazados seguidos.");
            }
        }
    }

    private boolean isBlocked(String ip) {
        Long until = blockedUntil.get(ip);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        blockedUntil.remove(ip);
        return false;
    }

    /**
     * BOTÓN DE EMERGENCIA del anfitrión (Laboratorio > ACCESO, 0.0.3.z): apaga el
     * servidor de esta PC, sea el de dentro del programa o "Servidor VS Online.exe"
     * (este lo ve por un archivo-señal que revisa cada 2 s). Avisa a todos y cierra
     * las conexiones.
     */
    public static void requestEmergencyShutdown() {
        try {
            // La HORA del pedido (2026-10-16): la señal vive en la carpeta config común y la ven los servidores de
            // la 0.0.3.z y de la 0.0.4. Cada uno se apaga si la señal es posterior a su arranque; nadie la borra
            // (antes el primero que la veía la borraba y el otro seguía encendido).
            java.nio.file.Files.writeString(emergencyFlag(), String.valueOf(System.currentTimeMillis()));
        } catch (IOException e) {
            log("No se pudo dejar la señal de apagado: " + e.getMessage());
        }
        VsServer embedded;
        synchronized (VsServer.class) {
            embedded = embeddedInstance;
        }
        if (embedded != null) embedded.shutdown("El anfitrión apagó el servidor.");
    }

    private static java.nio.file.Path emergencyFlag() {
        return org.example.chat.AppPaths.sharedConfig().resolve("apagar-servidor.senal");
    }

    /** Cuándo arrancó este servidor: una señal de emergencia anterior no lo apaga. */
    private final long startedAt = System.currentTimeMillis();

    /** ¿Pidieron apagar DESPUÉS de que este servidor arrancó? (señal vieja con "apagar": se usa la fecha del archivo) */
    private boolean emergencyRequested() {
        java.nio.file.Path flag = emergencyFlag();
        if (!java.nio.file.Files.exists(flag)) return false;
        try {
            return Long.parseLong(java.nio.file.Files.readString(flag).trim()) >= startedAt;
        } catch (IOException | NumberFormatException e) {
            try {
                return java.nio.file.Files.getLastModifiedTime(flag).toMillis() >= startedAt;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    /** Cuánto espera alguien la respuesta del anfitrión antes de que se le pida volver más tarde. */
    static final int WAIT_FOR_PERMISSION_SECONDS = 180;
    /** Conexiones que pidieron permiso y esperan (todavía no están en la sala). */
    private final Set<ClientConnection> waiting = ConcurrentHashMap.newKeySet();

    void waitForPermission(ClientConnection connection) {
        waiting.add(connection);
    }

    /**
     * Cada 2 s: relee la lista de acceso y las solicitudes. Saca de la sala a
     * quien ya no está (retirado, no baneado), deja entrar a quien esperaba y fue
     * aceptado, y despide a quien fue rechazado o esperó demasiado.
     */
    private void checkAccessList() {
        if (emergencyRequested()) { // botón de emergencia del anfitrión (desde la 0.0.3.z o la 0.0.4)
            shutdown("El anfitrión apagó el servidor.");
            return;
        }
        AccessList list = AccessList.load();
        for (ClientConnection c : clients.values()) {
            if (list.isAllowed(c.name, c.identity)) continue;
            c.send(Protocol.msg("error").put("code", "removed")
                    .put("msg", "El anfitrión te retiró del servidor. Tus funciones locales siguen disponibles."));
            c.closeGracefully();
            log("Retirado por la lista de acceso: " + c.name + " (#" + c.id + ")");
        }
        long now = System.currentTimeMillis();
        for (ClientConnection c : waiting) {
            String name = c.requestedName;
            if (name == null) {
                waiting.remove(c);
            } else if (list.isAllowed(name, c.identity)) {
                waiting.remove(c);
                log("Aceptado por el anfitrión: " + name + " (" + c.identityLabel + ")");
                if (!c.enter(name)) c.closeGracefully();
            } else if (!AccessList.hasRequest(name, c.identity)) {
                waiting.remove(c);
                c.send(Protocol.msg("error").put("code", "rejected").put("msg", "El anfitrión no aceptó tu solicitud"
                        + " esta vez. Las funciones locales siguen disponibles."));
                c.closeGracefully();
                log("Solicitud rechazada: " + name + " (" + c.identityLabel + ")");
            } else if (now - c.waitingSince > WAIT_FOR_PERMISSION_SECONDS * 1000L) {
                waiting.remove(c);
                c.send(Protocol.msg("error").put("code", "pendingTimeout").put("msg", "El anfitrión todavía no responde."
                        + " Tu solicitud queda guardada: si te acepta, la próxima vez entrarás directo."));
                c.closeGracefully();
            }
        }
    }

    /** Anota quiénes están en la sala (el Laboratorio del anfitrión lo muestra). */
    private void publishOnline() {
        AccessList.writeOnline(clients.values().stream().map(c -> c.name).toList());
    }

    /** ¿Hay un servidor corriendo dentro de este programa? */
    public static synchronized boolean embeddedRunning() {
        return embeddedInstance != null && !embeddedInstance.shuttingDown;
    }

    /** Apagado ordenado: avisa, cierra conexiones y sale. Idempotente. */
    void shutdown(String reason) {
        if (shuttingDown) return;
        shuttingDown = true;
        log("Apagando: " + reason);
        for (ClientConnection c : connections) {
            c.send(Protocol.msg("error").put("msg", reason));
            c.closeGracefully();
        }
        ticker.shutdownNow();
        AccessList.writeOnline(List.of());
        try {
            ServerSocket ss = serverSocket;
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
        if (embedded) { // dentro del V-Pet: nunca cerrar el programa entero; se puede volver a encender
            synchronized (VsServer.class) {
                if (embeddedInstance == this) embeddedInstance = null;
            }
            return;
        }
        // Da un instante a que salgan los avisos y termina el proceso.
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }, "vs-exit");
        exit.start();
    }

    OfficialBattles battles() { return battles; }

    Tournaments tournaments() { return tournaments; }

    /** Jugador en la sala por su nombre (sin mayúsculas), o null: los torneos siguen a cada uno por su nombre. */
    ClientConnection playerNamed(String name) {
        for (ClientConnection c : clients.values()) if (c.name != null && c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    /** Jugador en la sala por su número, o null. */
    ClientConnection player(int id) { return clients.get(id); }

    java.util.Collection<ClientConnection> players() { return clients.values(); }

    // ---------------------------------------------------------------- sala

    /** Llamado por la conexión tras un "hello" válido. Devuelve el id asignado, o ROOM_FULL. */
    synchronized int join(ClientConnection connection, String name) {
        if (clients.size() >= Protocol.MAX_PLAYERS_PER_ROOM) return ROOM_FULL;
        int id = nextId.getAndIncrement();
        connection.id = id;
        connection.name = name;
        // Aparece en una casilla de entrada al azar (salida sur del mapa).
        List<double[]> spawns = LobbyMap.spawnPoints();
        double[] spawn = spawns.get((int) (Math.random() * spawns.size()));
        connection.x = spawn[0];
        connection.y = spawn[1];
        connection.path = List.of();
        clients.put(id, connection);

        // "name": con el que entró de verdad (una cuenta aceptada entra con su nombre registrado).
        connection.send(Protocol.msg("welcome").put("id", id).put("name", name).put("room", ROOM_NAME).put("map", LobbyMap.ID)
                .put("w", Protocol.ROOM_WIDTH).put("h", Protocol.ROOM_HEIGHT));
        broadcast(Protocol.msg("joined").put("id", id).put("name", name));
        connection.send(snapshot());
        // Los Digimon de quienes ya estaban en la sala.
        for (ClientConnection other : clients.values()) {
            JSONObject d = other.digimon;
            if (other != connection && d != null) connection.send(d);
        }
        tournaments.welcome(connection); // v12: los torneos que hay (si vuelve tras un corte, sigue en el suyo)
        log(name + " (#" + id + ") entró. Jugadores: " + clients.size());
        publishOnline();
        return id;
    }

    synchronized void leave(ClientConnection connection) {
        waiting.remove(connection); // se fue mientras esperaba permiso: su solicitud queda para el anfitrión
        if (connection.id <= 0 || clients.remove(connection.id) == null) return;
        battles.onLeave(connection);
        tournaments.onLeave(connection);
        broadcast(Protocol.msg("left").put("id", connection.id).put("name", connection.name));
        log(connection.name + " (#" + connection.id + ") salió. Jugadores: " + clients.size());
        publishOnline();
    }

    /**
     * Digimon ya validado por la conexión. El servidor arma el mensaje que se
     * reparte: solo los campos revisados, la especie limpiada y el rango
     * calculado AQUÍ (nunca el que diga el cliente). Los Power Trophies y
     * los stats no se reparten (decisión del usuario: no mostrar stats).
     */
    void digimonArrived(ClientConnection from, JSONObject validated) {
        String species = Protocol.cleanText(validated.optString("species"), Protocol.MAX_NAME_LENGTH);
        JSONObject message = Protocol.msg("digimon").put("id", from.id)
                .put("species", species.isEmpty() ? "Digimon" : species)
                .put("attribute", validated.getInt("attribute"))
                .put("stage", validated.getInt("stage"))
                .put("rank", config.rankFor(validated.getInt("powerTrophies")))
                .put("frames", validated.getJSONArray("frames"))
                .put("nameSprite", validated.getJSONObject("nameSprite"))
                // v11: copia del LAB (solo se acepta un booleano verdadero; cualquier otra cosa = no es copia).
                .put("copy", isCopy(validated))
                // v12: premio del campeón de un torneo, encendido: se ve al doble (un adorno; no cambia la pelea).
                .put("big", isFlag(validated, "big"));
        JSONObject s = validated.getJSONObject("stats");
        from.stats = new int[]{s.getInt("dp"), s.getInt("hp"), s.getInt("ap"),
                s.getInt("small"), s.getInt("big"), s.getInt("activity")};
        from.stage = validated.getInt("stage");
        from.powerTrophies = validated.getInt("powerTrophies");

        // Compañero (puesto 2), solo para el 2 vs 2: se reparten sus cuadros, nunca sus stats.
        JSONObject p = validated.optJSONObject("partner");
        if (p != null) {
            String pSpecies = Protocol.cleanText(p.optString("species"), Protocol.MAX_NAME_LENGTH);
            message.put("partner", new JSONObject()
                    .put("species", pSpecies.isEmpty() ? "Digimon" : pSpecies)
                    .put("attribute", p.getInt("attribute"))
                    .put("stage", p.getInt("stage"))
                    .put("rank", config.rankFor(p.getInt("powerTrophies")))
                    .put("frames", p.getJSONArray("frames"))
                    .put("nameSprite", p.getJSONObject("nameSprite"))
                    .put("copy", isCopy(p))
                    .put("big", isFlag(p, "big")));
            JSONObject ps = p.getJSONObject("stats");
            from.partnerStats = new int[]{ps.getInt("dp"), ps.getInt("hp"), ps.getInt("ap"),
                    ps.getInt("small"), ps.getInt("big"), ps.getInt("activity")};
            from.partnerStage = p.getInt("stage");
            from.partnerPowerTrophies = p.getInt("powerTrophies");
            from.partnerAttribute = p.getInt("attribute");
        } else {
            from.partnerStats = null;
        }
        from.lookKey = Tournaments.lookKey(validated);
        from.digimon = message;
        broadcast(message);
        log(from.name + " (#" + from.id + ") trajo a " + message.getString("species")
                + " (rango " + message.getString("rank") + ")");
    }

    /** Protocolo v11: ¿es una copia del LAB? Solo un booleano true cuenta (nunca se repite lo que mande tal cual). */
    static boolean isCopy(JSONObject digimon) {
        return isFlag(digimon, "copy");
    }

    /** Un dato sí/no del Digimon: solo un booleano true cuenta. */
    static boolean isFlag(JSONObject digimon, String key) {
        return digimon != null && digimon.opt(key) instanceof Boolean b && b;
    }

    /** Solo a los clientes que entienden los torneos (v12 o más). */
    void broadcastV12(JSONObject message) {
        String line = Protocol.line(message);
        for (ClientConnection c : clients.values()) if (c.version >= Protocol.TOURNAMENT_MIN_VERSION) c.sendRaw(line);
    }

    void chat(ClientConnection from, String text) {
        broadcast(Protocol.msg("chat").put("id", from.id).put("name", from.name).put("text", text));
        log("[chat] " + from.name + ": " + text);
    }

    // ---------------------------------------------------------------- tick

    private void tick() {
        try {
            double step = SPEED_UNITS_PER_SECOND / TICKS_PER_SECOND;
            for (ClientConnection c : clients.values()) {
                synchronized (c.moveLock) { // un clic nuevo no puede cambiar el camino a mitad de este paso
                // Sigue su camino punto por punto; el camino ya rodea los muros.
                double budget = step;
                List<double[]> path = c.path;
                int index = c.pathIndex;
                while (budget > 0 && index < path.size()) {
                    double[] p = path.get(index);
                    double dx = p[0] - c.x, dy = p[1] - c.y;
                    double dist = Math.hypot(dx, dy);
                    if (dist <= budget) {
                        c.x = p[0];
                        c.y = p[1];
                        budget -= dist;
                        index++;
                    } else {
                        c.x += dx / dist * budget;
                        c.y += dy / dist * budget;
                        budget = 0;
                    }
                }
                c.pathIndex = index;
                }
            }
            if (++tickCount % SNAPSHOT_EVERY_TICKS == 0 && !clients.isEmpty()) {
                broadcast(snapshot());
            }
        } catch (Exception e) {
            // Un error en un tick nunca debe matar el hilo del ticker.
            log("Error en tick: " + e);
        }
    }

    private JSONObject snapshot() {
        JSONArray players = new JSONArray();
        for (ClientConnection c : clients.values()) {
            players.put(new JSONObject().put("id", c.id).put("name", c.name)
                    .put("x", Math.round(c.x)).put("y", Math.round(c.y)));
        }
        return Protocol.msg("snapshot").put("players", players);
    }

    void broadcast(JSONObject message) {
        String line = Protocol.line(message);
        for (ClientConnection c : clients.values()) c.sendRaw(line);
    }

    static void log(String text) {
        System.out.println("[VS " + java.time.LocalTime.now().withNano(0) + "] " + text);
    }
}
