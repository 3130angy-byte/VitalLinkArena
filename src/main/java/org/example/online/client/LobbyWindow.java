package org.example.online.client;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import org.example.lab.LabWindow;
import org.example.online.LobbyMap;
import org.example.online.Protocol;
import org.example.online.server.VsServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.io.IOException;
import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;

/**
 * Ventana de la sala del VS Online para UN jugador: el 1er piso (LobbyMap,
 * dibujo provisional), el Digimon de cada jugador (desde el 2026-10-18 no hay
 * avatar humano: el Digimon ES el jugador y se mueve con clic) con
 * su rango, avisos de NPC/lugares al acercarse y chat entre jugadores.
 * Clic para caminar (el servidor calcula el camino alrededor de los muros).
 *
 * La usan el programa principal (Batalla > VS Online, tras la animación del
 * portal) y la ventana de prueba LobbyTestWindow. Al cerrarse avisa con
 * onClosed (el programa principal la usa para la animación de salida).
 */
public final class LobbyWindow {

    /**
     * CÁMARA (pedido del usuario): no se ve toda la sala, sino la zona
     * alrededor de tu Digimon, acercada x2. Con ZOOM 2 el Digimon (que en el
     * mundo mide la mitad de su sprite) se ve con sus píxeles originales 1:1.
     */
    private static final double ZOOM = 2.0;
    private static final double VIEW_WIDTH = 960, VIEW_HEIGHT = 600;
    /** La cámara alcanza a tu Digimon suavemente (fracción por segundo, no por cuadro). */
    private static final double CAMERA_FOLLOW_PER_SECOND = 8;
    /** Los pies quedan unos píxeles bajo el centro de la casilla, para que el Digimon "pise" su baldosa. */
    private static final double FEET_OFFSET = 5;
    /**
     * El servidor manda 10 fotos por segundo. Se dibuja este tiempo "en el
     * pasado" y se interpola entre fotos (ver render). Antes el dibujo se
     * acercaba un % por cuadro: arrancaba de golpe y frenaba 10 veces por
     * segundo (el efecto "látigo"). 120 ms = poco más de una foto de margen.
     */
    private static final long INTERP_DELAY_NANOS = 120_000_000L;
    /** Un salto mayor que esto (entró a la sala, se reconectó) no se anima como caminata. */
    private static final double SNAP_DISTANCE = 64;
    /** Fotos recientes del servidor por jugador: {tiempo de llegada, x, y}. */
    private final Map<Integer, java.util.ArrayDeque<double[]>> histories = new HashMap<>();
    /** A esta distancia (unidades del mundo) de un NPC o lugar, aparece su aviso. */
    private static final double NEAR_DISTANCE = 44;
    /** Minimapa en la esquina superior derecha (unidades de pantalla). */
    private static final double MINIMAP_WIDTH = 170;

    private double cameraX = Double.NaN, cameraY = Double.NaN;
    private long lastFrameNanos = -1;


    private final Stage stage;
    private final String host;
    private final int port;
    private final String name;
    private final int windowIndex;
    /** Mi Digimon listo para mandar al entrar (null = entro sin Digimon). */
    private final JSONObject myDigimon;
    private final String title;
    private final Runnable onClosed;
    private AnimationTimer renderLoop;
    private OfficialBattleUi battleUi;
    /** Torneos (v12): diálogo de la Recepción de torneos, el aviso de LISTO y el premio. */
    private TournamentUi tournamentUi;
    /** El nombre con que entré de verdad (una cuenta aceptada entra con su nombre registrado). */
    private String myName;
    private static Consumer<String> onTournamentPrize = mode -> { };
    /** Ganó un torneo: el equipo (ya con el premio) se reenvía cuando termine de ver la pelea. */
    private boolean resendTeamWhenFree;
    private Consumer<OfficialBattleResult> onBattleResult;
    private boolean wasNearPc = false;
    /** Arma el mensaje "digimon" con tu equipo actual (puesto 1 + compañero). Null = se usa myDigimon fijo. */
    private Supplier<JSONObject> teamSupplier;
    /** Salas abiertas: al cambiar el equipo en el Laboratorio se les avisa (notifyTeamChanged). */
    private static final List<LobbyWindow> OPEN = new ArrayList<>();

    private VsClient client = new VsClient();
    private final Canvas canvas = new Canvas(VIEW_WIDTH, VIEW_HEIGHT);
    private final ListView<String> chatLog = new ListView<>();
    private final TextField chatInput = new TextField();
    private final Label status = new Label("Conectando...");

    /** id -> {x, y} según el servidor, y la posición suavizada que se dibuja. */
    private final Map<Integer, double[]> serverPos = new HashMap<>();
    private final Map<Integer, double[]> drawPos = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private int myId = -1;
    private Image mapImage;
    /**
     * Sin avatar humano (pedido del usuario, 2026-10-18): el Digimon de cada jugador ES el jugador. Hacia dónde
     * mira y si está caminando se deducen de cómo se mueve su posición (el servidor solo manda posiciones).
     */
    private final Map<Integer, WalkAnim> anims = new HashMap<>();
    private final Map<Integer, LobbyDigimon> digimons = new HashMap<>();
    /** Cambios de Digimon por portal en curso, por jugador (LobbyPortalSwap). */
    private final Map<Integer, LobbyPortalSwap> swaps = new HashMap<>();
    /** El servidor rechazó el equipo nuevo con el portal abierto: se reintenta UNA vez. */
    private boolean teamRetried = false;

    /**
     * @param title     título de la ventana
     * @param onClosed  se llama una vez al cerrar la ventana (p. ej. para la animación de salida del portal)
     */
    public LobbyWindow(Stage stage, String host, int port, String name, int windowIndex,
                       JSONObject myDigimon, String title, Runnable onClosed) {
        this.title = title;
        this.onClosed = onClosed;
        this.myDigimon = myDigimon;
        this.stage = stage;
        this.host = host;
        this.port = port;
        this.name = name;
        this.windowIndex = windowIndex;
    }

    /** El equipo se toma de aquí al entrar y cada vez que cambia en la PC/Laboratorio. */
    public void setTeamSupplier(Supplier<JSONObject> teamSupplier) {
        this.teamSupplier = teamSupplier;
    }

    /** Lo llama el programa principal tras cambiar el equipo (puestos 1 y 2): se reenvía al servidor. */
    public static void notifyTeamChanged() {
        for (LobbyWindow lobby : List.copyOf(OPEN)) {
            if (lobby.teamSupplier == null || lobby.myId < 0) continue;
            JSONObject team = lobby.teamSupplier.get();
            if (team != null) lobby.client.sendDigimon(team);
        }
    }

    /**
     * Tu Digimon de la sala empieza a irse por un portal que queda abierto;
     * el que llega sale por él cuando el servidor reparta el equipo nuevo
     * (notifyTeamChanged). Lo llama el programa principal al reemplazar o
     * intercambiar el puesto 1 desde la PC.
     */
    public static void beginPortalSwap() {
        for (LobbyWindow lobby : List.copyOf(OPEN)) lobby.startMyDeparture();
    }

    /** Hay una sala abierta (solo se entra con un Digimon a la vez). */
    public static boolean isAnyOpen() {
        return !OPEN.isEmpty();
    }

    /** Por qué no se puede cambiar el equipo ahora mismo, o null si se puede. */
    public static String teamChangeBlocker() {
        for (LobbyWindow lobby : OPEN) {
            if (lobby.battleUi != null && lobby.battleUi.isBusy()) {
                return "No puedes cambiar tu equipo durante un reto o una pelea del VS Online.";
            }
            if (lobby.swaps.containsKey(lobby.myId)) return "Espera a que termine el cambio por el portal.";
            String inTournament = lobby.tournamentUi == null ? null : lobby.tournamentUi.teamChangeBlocker();
            if (inTournament != null) return inTournament;
        }
        return null;
    }

    private void startMyDeparture() {
        LobbyDigimon mine = digimons.get(myId);
        if (mine == null || Double.isNaN(mine.x) || swaps.containsKey(myId)) return;
        teamRetried = false;
        swaps.put(myId, new LobbyPortalSwap(mine, mine.facingRight, null, System.nanoTime()));
    }

    private JSONObject currentTeam() {
        return teamSupplier != null ? teamSupplier.get() : myDigimon;
    }

    public void open() {
        OPEN.add(this);
        status.setStyle("-fx-text-fill: #cfd8e3;");
        status.setPadding(new Insets(6, 10, 6, 10));

        chatLog.setPrefWidth(260);
        chatInput.setPromptText("Escribe y presiona Enter");
        chatInput.setOnAction(e -> {
            String text = chatInput.getText().trim();
            if (!text.isEmpty()) client.chat(text);
            chatInput.clear();
        });
        VBox chatBox = new VBox(6, chatLog, chatInput);
        chatBox.setPadding(new Insets(6));
        VBox.setVgrow(chatLog, Priority.ALWAYS);

        // Clic en pantalla -> punto del mundo, según dónde esté la cámara.
        canvas.setOnMouseClicked(e -> {
            if (Double.isNaN(cameraX)) return;
            client.moveTo(cameraX + e.getX() / ZOOM, cameraY + e.getY() / ZOOM);
        });

        // Centro = el mapa + una capa encima para los diálogos del NPC y de los retos.
        StackPane center = new StackPane(canvas);
        BorderPane root = new BorderPane(center);
        battleUi = new OfficialBattleUi(() -> client, digimons, () -> myId, root, center,
                canvas.getWidth(), canvas.getHeight(), this::addChatLine);
        battleUi.setOnResult(onBattleResult);
        battleUi.setArenaHooks(arenaBefore, arenaAfter);
        battleUi.setBattleBlocker(battleBlocker);
        center.getChildren().add(battleUi.layer);
        tournamentUi = new TournamentUi(() -> client, () -> myName != null ? myName : name, this::addChatLine);
        tournamentUi.setOnPrize(mode -> {
            onTournamentPrize.accept(mode); // el programa principal guarda el premio en su Digimon
            resendTeamWhenFree = true;      // y, al terminar de ver la final, todos lo ven al doble
        });
        center.getChildren().add(tournamentUi.layer);
        root.setTop(status);
        root.setRight(chatBox);
        root.setStyle("-fx-background-color: #1b1f2a;");

        stage.setTitle(title);
        stage.setScene(new Scene(root));
        stage.setX(40 + windowIndex * 60);
        stage.setY(40 + windowIndex * 60);
        // En un torneo, cerrar la sala = quedar fuera de ese torneo (decisión del usuario): se avisa antes.
        stage.setOnCloseRequest(e -> {
            String warning = tournamentUi == null ? null : tournamentUi.leaveWarning();
            if (warning == null) return;
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION, warning,
                    javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
            confirm.initOwner(stage);
            confirm.setTitle("VS Online");
            confirm.setHeaderText("¿Salir de la sala?");
            if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.NO) == javafx.scene.control.ButtonType.YES) {
                tournamentUi.leaveActive();
            } else {
                e.consume();
            }
        });
        stage.setOnHidden(e -> {
            OPEN.remove(this);
            client.close();
            if (renderLoop != null) renderLoop.stop();
            if (onClosed != null) onClosed.run();
        });
        stage.show();

        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                render();
            }
        };
        renderLoop.start();

        try {
            connect();
        } catch (Exception first) {
            // Sin servidor en ESTA PC: se arranca uno dentro del programa y se reintenta,
            // para que siempre aparezcas en la sala con tu Digimon.
            if (isLocalHost(host) && VsServer.startEmbedded(port)) {
                try {
                    client = new VsClient(); // un socket que falló no se puede reutilizar
                    connect();
                    return;
                } catch (Exception second) {
                    first = second;
                }
            }
            status.setText("No se pudo conectar a " + host + ":" + port + " (" + first.getMessage() + ")"
                    + (isLocalHost(host) ? "" : " -- ¿el anfitrión tiene el servidor abierto y Tailscale conectado?"));
        }
    }

    private void connect() throws IOException {
        client.connect(host, port, name,
                msg -> Platform.runLater(() -> onMessage(msg)),
                reason -> Platform.runLater(() -> status.setText("Desconectado: " + reason)));
    }

    private static boolean isLocalHost(String host) {
        return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost");
    }

    private void onMessage(JSONObject m) {
        if (tournamentUi != null) {
            String type = m.optString("t");
            if (type.equals("battle") || type.equals("arenaStart")) tournamentUi.battleStarted();
            if (tournamentUi.handle(m)) return; // torneos (v12)
        }
        if (battleUi != null && battleUi.handle(m)) return; // disponibles, retos y peleas
        switch (m.optString("t")) {
            case "welcome" -> {
                myId = m.getInt("id");
                JSONObject team = currentTeam();
                if (team != null) client.sendDigimon(team);
                // El servidor dice con qué nombre entraste: una cuenta ya aceptada entra con el suyo registrado.
                String entered = m.optString("name", name);
                myName = entered;
                status.setText("Conectado como " + entered + " a " + host + ":" + port + " (" + m.optString("room") + ")");
                if (!entered.equalsIgnoreCase(name)) addChatLine("* Entraste como " + entered + ", el nombre registrado de tu cuenta.");
            }
            case "snapshot" -> {
                JSONArray players = m.getJSONArray("players");
                Map<Integer, double[]> fresh = new HashMap<>();
                for (int i = 0; i < players.length(); i++) {
                    JSONObject p = players.getJSONObject(i);
                    int id = p.getInt("id");
                    fresh.put(id, new double[]{p.getDouble("x"), p.getDouble("y")});
                    names.put(id, p.getString("name"));
                    drawPos.putIfAbsent(id, new double[]{p.getDouble("x"), p.getDouble("y")});
                }
                serverPos.clear();
                serverPos.putAll(fresh);
                drawPos.keySet().retainAll(fresh.keySet());
                anims.keySet().retainAll(fresh.keySet());
                digimons.keySet().retainAll(fresh.keySet());
                swaps.keySet().retainAll(fresh.keySet());
            }
            case "joined" -> addChatLine("* " + m.optString("name") + " entró a la sala");
            case "left" -> addChatLine("* " + m.optString("name") + " salió de la sala");
            case "digimon" -> {
                try {
                    int id = m.getInt("id");
                    LobbyDigimon fresh = LobbyDigimon.fromMessage(m);
                    LobbyDigimon old = digimons.put(id, fresh);
                    LobbyPortalSwap swap = swaps.get(id);
                    if (swap != null && swap.waiting()) {
                        swap.arrive(fresh); // tu portal ya está abierto: el nuevo sale por él
                    } else if (old != null && swap == null && old.look != fresh.look && !Double.isNaN(old.x)) {
                        // Otro Digimon en el puesto 1: se va uno por el portal y sale el otro.
                        swaps.put(id, new LobbyPortalSwap(old, old.facingRight, fresh, System.nanoTime()));
                    } else if (old != null) {
                        fresh.takePlaceOf(old); // mismo Digimon (p. ej. solo cambió el compañero): sin saltos
                    }
                    if (id == myId) teamRetried = false;
                    if (old != null && id == myId) addChatLine("* Tu equipo se actualizó.");
                } catch (RuntimeException ex) {
                    System.out.println("Digimon inválido recibido, ignorado: " + ex.getMessage());
                }
            }
            case "teamRejected" -> {
                addChatLine("* " + m.optString("reason"));
                LobbyPortalSwap swap = swaps.get(myId);
                if (swap == null || !swap.waiting()) return;
                if (teamRetried) {
                    swap.arrive(digimons.get(myId)); // no se pudo: vuelve a salir el mismo
                    return;
                }
                // Con el portal abierto, un rechazo por "espera un momento" se reintenta solo.
                teamRetried = true;
                javafx.animation.PauseTransition retry = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5.5));
                retry.setOnFinished(e -> {
                    JSONObject team = currentTeam();
                    if (team != null && OPEN.contains(this)) client.sendDigimon(team);
                });
                retry.play();
            }
            case "chat" -> addChatLine(m.optString("name") + ": " + m.optString("text"));
            case "accessPending" -> {
                // Pediste permiso: el servidor te deja entrar solo cuando el anfitrión acepta.
                status.setText("Esperando el permiso de " + m.optString("host", "el anfitrión") + "...");
                addChatLine("* " + m.optString("msg"));
                addChatLine("* Si en " + (m.optInt("seconds", 180) / 60) + " minutos no responde, tu solicitud queda guardada.");
            }
            case "error" -> {
                String code = m.optString("code");
                String header = switch (code) {
                    case "notAllowed" -> "No puedes entrar a la sala";
                    case "removed" -> "Saliste del servidor";
                    case "rejected" -> "El anfitrión no aceptó tu solicitud";
                    case "pendingTimeout" -> "Sin respuesta del anfitrión";
                    default -> null;
                };
                if (header != null) {
                    // Acceso del anfitrión: sin permiso no hay sala; se cierra y tu Digimon vuelve.
                    status.setText(m.optString("msg"));
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.INFORMATION, m.optString("msg"));
                    alert.initOwner(stage);
                    alert.setTitle("VS Online");
                    alert.setHeaderText(header);
                    alert.setOnHidden(e -> stage.close());
                    alert.show();
                } else {
                    status.setText("Error del servidor: " + m.optString("msg"));
                }
            }
            default -> { }
        }
    }

    /**
     * Un jugador = su Digimon (sin avatar, 2026-10-18): el Digimon (o el cambio por portal en curso) y encima
     * su nombre y rango. Si su Digimon todavía no llegó, solo una sombra y el nombre.
     */
    private void drawPlayer(GraphicsContext g, int id, double[] pos, long now) {
        double px = pos[0], feet = pos[1] + FEET_OFFSET;
        LobbyPortalSwap swap = swaps.get(id);
        LobbyDigimon d = digimons.get(id);
        double height = 14;
        if (swap != null) {
            swap.draw(g, now, FEET_OFFSET);
            height = swap.height();
        } else if (d != null && !Double.isNaN(d.y)) {
            drawDigimon(g, d);
            height = d.currentFrame().getHeight() * d.worldScale();
        } else {
            g.setFill(Color.rgb(0, 0, 0, 0.18));
            g.fillOval(px - 7, feet - 1.5, 14, 3);
        }
        drawLabel(g, id, px, feet - height);
    }

    /** Nombre del jugador y, si trajo Digimon, el rango de ese Digimon (nunca stats), encima de {@code top}. */
    private void drawLabel(GraphicsContext g, int id, double px, double top) {
        String label = names.getOrDefault(id, "?");
        LobbyDigimon d = digimons.get(id);
        // Texto en el mundo: con el acercamiento x2 se ve de 13 px (el texto del lienzo es vectorial, queda nítido).
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 6.5));
        g.setFill(Color.rgb(0, 0, 0, 0.55));
        g.fillText(label, px + 0.5 - (d != null && LobbyDigimon.hasRank(d.rank) ? 4 : 0), top - 2 + 0.5);
        g.setFill(Color.WHITE);
        if (d == null || !LobbyDigimon.hasRank(d.rank)) {
            g.fillText(label, px, top - 2);
        } else {
            double textW = label.length() * 3.6;
            g.fillText(label, px - 4, top - 2);
            drawRankBadge(g, d.rank, px - 4 + textW / 2 + 5, top - 4.5);
        }
        if (id == myId) { // marcador de "este eres tú"
            g.setFill(Color.rgb(90, 220, 255));
            g.fillPolygon(new double[]{px - 2.5, px + 2.5, px}, new double[]{top - 13, top - 13, top - 9.5}, 3);
        }
    }

    /**
     * El Digimon con los pies en su posición, espejado al ir a la derecha. En
     * el mundo mide la mitad de su sprite, y con
     * la cámara x2 se ve con sus píxeles originales 1:1.
     */
    private void drawDigimon(GraphicsContext g, LobbyDigimon d) {
        Image frame = d.currentFrame();
        double w = frame.getWidth() * d.worldScale(), h = frame.getHeight() * d.worldScale(); // premio: al doble
        double px = snap(d.x), feet = snap(d.y + FEET_OFFSET); // en un píxel de pantalla exacto
        g.setFill(Color.rgb(0, 0, 0, 0.18));
        g.fillOval(px - w * 0.3, feet - 1.5, w * 0.6, 3);
        if (d.facingRight) {
            g.save();
            g.translate(px, 0);
            g.scale(-1, 1); // convención del proyecto: derecha = espejado
            g.drawImage(frame, -w / 2, feet - h, w, h);
            g.restore();
        } else {
            g.drawImage(frame, px - w / 2, feet - h, w, h);
        }
        if (d.copy) drawCopySparks(g, px, feet, w, h);
    }

    /**
     * Protocolo v11: los píxeles celestes que suben alrededor de una COPIA (los cuadros ya vienen teñidos). Se
     * calculan con el reloj (sin guardar estado), a saltos de 0,1 s como en el escritorio.
     * Un píxel del mundo = 2 de pantalla con la cámara x2, el mismo tamaño que en el escritorio.
     */
    private void drawCopySparks(GraphicsContext g, double px, double feet, double w, double h) {
        long step = System.nanoTime() / 100_000_000L;
        for (int i = 0; i < 6; i++) {
            int life = 6 + i % 4;
            long cycle = (step + i * 3L) / life;
            int age = (int) ((step + i * 3L) % life);
            long seed = cycle * 31 + i * 7919L;
            double sx = px - w / 2 - 1 + Math.floorMod(seed * 1103515245L + 12345, (long) (w + 2));
            double sy = feet - h / 3 - Math.floorMod(seed * 69069L, (long) Math.max(1, h * 2 / 3)) - age;
            g.setFill(Color.rgb(95, 230, 255, Math.min(1.0, 0.25 + (double) (life - age) / life)));
            g.fillRect(Math.round(sx), Math.round(sy), 1, 1);
        }
    }

    /** Insignia redonda con la letra del rango: C gris, B azul, A naranja, S dorado (colores provisionales). */
    private void drawRankBadge(GraphicsContext g, String rank, double cx, double cy) {
        Color fill = switch (rank) {
            case "S" -> Color.rgb(240, 190, 40);
            case "A" -> Color.rgb(240, 130, 40);
            case "B" -> Color.rgb(60, 140, 240);
            default -> Color.rgb(140, 140, 150);
        };
        g.setFill(Color.rgb(20, 20, 28));
        g.fillOval(cx - 4.5, cy - 4.5, 9, 9);
        g.setFill(fill);
        g.fillOval(cx - 3.8, cy - 3.8, 7.6, 7.6);
        g.setFill(Color.WHITE);
        g.fillText(rank, cx, cy + 2.3);
    }

    private void addChatLine(String line) {
        chatLog.getItems().add(line);
        chatLog.scrollTo(chatLog.getItems().size() - 1);
    }

    private void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        if (mapImage == null) mapImage = LobbyMapRenderer.renderStatic(ZOOM);
        long now = System.nanoTime();
        // Segundos desde el cuadro anterior: el movimiento no depende de los FPS (tope por si la ventana se congeló).
        double dt = lastFrameNanos < 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;

        // 1) Posiciones dibujadas: INTERPOLACIÓN CON RETRASO (la técnica de los juegos en red).
        // Cada personaje se dibuja INTERP_DELAY "en el pasado", recorriendo en línea recta
        // el tramo entre las dos fotos del servidor que rodean ese instante: avanza a la
        // velocidad exacta del servidor, sin tirones ni esperas entre fotos.
        long renderTime = now - INTERP_DELAY_NANOS;
        for (Map.Entry<Integer, double[]> e : drawPos.entrySet()) {
            int id = e.getKey();
            double[] pos = e.getValue();
            double[] target = serverPos.get(id);
            java.util.ArrayDeque<double[]> history = histories.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
            if (target != null) {
                double[] newest = history.peekLast();
                if (newest == null || newest[1] != target[0] || newest[2] != target[1]) {
                    history.addLast(new double[]{now, target[0], target[1]});
                    while (history.size() > 12) history.pollFirst();
                }
            }
            double[] interpolated = interpolate(history, renderTime);
            double dx = 0, dy = 0;
            if (interpolated != null) {
                dx = interpolated[0] - pos[0];
                dy = interpolated[1] - pos[1];
                if (Math.hypot(dx, dy) > SNAP_DISTANCE) dx = dy = 0; // salto (entró a la sala): sin animar
                pos[0] = interpolated[0];
                pos[1] = interpolated[1];
            }
            WalkAnim anim = anims.computeIfAbsent(id, k -> new WalkAnim());
            anim.update(dx, dy, now);
            LobbyDigimon d = digimons.get(id);
            if (d != null && !swaps.containsKey(id)) d.ride(pos[0], pos[1], anim.facingRight, anim.walking, now);
        }
        histories.keySet().retainAll(drawPos.keySet());
        // Cambios por portal: al terminar, el nuevo quedó en el lugar del jugador mirando hacia el portal cerrado.
        swaps.entrySet().removeIf(e -> {
            LobbyDigimon d = digimons.get(e.getKey());
            if (!e.getValue().update(now, d)) return false;
            WalkAnim anim = anims.get(e.getKey());
            if (anim != null && d != null) anim.facingRight = d.facingRight;
            return true;
        });

        // 2) Cámara: centrada en MI Digimon, sin salirse del mapa.
        double viewW = canvas.getWidth() / ZOOM, viewH = canvas.getHeight() / ZOOM;
        double[] me = drawPos.get(myId);
        double focusX = me != null ? me[0] : LobbyMap.WIDTH / 2.0, focusY = me != null ? me[1] - 8 : LobbyMap.HEIGHT / 2.0;
        double wantX = clamp(focusX - viewW / 2, 0, LobbyMap.WIDTH - viewW);
        double wantY = clamp(focusY - viewH / 2, 0, LobbyMap.HEIGHT - viewH);
        if (Double.isNaN(cameraX) || dt == 0) {
            cameraX = wantX;
            cameraY = wantY;
        } else {
            double k = Math.min(1, CAMERA_FOLLOW_PER_SECOND * dt);
            cameraX += (wantX - cameraX) * k;
            cameraY += (wantY - cameraY) * k;
        }

        // 3) El mundo, con la cámara aplicada.
        g.setImageSmoothing(false); // pixel art nítido
        g.setFill(Color.rgb(10, 14, 24));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.save();
        g.scale(ZOOM, ZOOM);
        // La cámara cae justo en un píxel de pantalla: así cada píxel de los sprites mide siempre lo mismo (pixel art).
        g.translate(-snap(cameraX), -snap(cameraY));
        g.drawImage(mapImage, 0, 0, LobbyMap.WIDTH, LobbyMap.HEIGHT);
        LobbyMapRenderer.drawPortalAnimation(g, now); // portal decorativo, animado
        g.setTextAlign(TextAlignment.CENTER);

        // Los jugadores (sus Digimon), ordenados por la altura de sus pies
        // (el que está más abajo en pantalla tapa al de atrás).
        List<Object[]> drawables = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : drawPos.entrySet()) {
            drawables.add(new Object[]{e.getValue()[1], (Runnable) () -> drawPlayer(g, e.getKey(), e.getValue(), now)});
        }
        drawables.sort(Comparator.comparingDouble(o -> (double) o[0]));
        drawables.forEach(o -> ((Runnable) o[1]).run());
        g.restore();

        // 4) En pantalla (sin cámara): aviso del NPC o lugar cercano a MI jugador y minimapa.
        // El NPC de Batalla oficial no usa globo: abre su diálogo al llegar (OfficialBattleUi).
        boolean nearRanking = false;
        boolean nearReception = false;
        boolean nearPc = false;
        if (me != null) {
            for (LobbyMap.Feature f : LobbyMap.FEATURES) {
                boolean isNear = Math.hypot(me[0] - f.x(), me[1] - f.y()) <= NEAR_DISTANCE;
                if (f.type() == LobbyMap.PC) nearPc = isNear;
                if (f.type() == LobbyMap.NPC_RANKING) {
                    nearRanking = isNear;
                    continue;
                }
                if (f.type() == LobbyMap.NPC_RECEPTION) { // v12: abre el diálogo de torneos al llegar
                    nearReception = isNear;
                    continue;
                }
                if (isNear) {
                    LobbyMapRenderer.drawBubble(g, f, (canvas.getWidth() - LobbyMapRenderer.BUBBLE_WIDTH) / 2, 12);
                }
            }
        }
        drawMinimap(g, viewW, viewH);
        drawTournamentStatus(g);
        if (resendTeamWhenFree && myId >= 0 && battleUi != null && !battleUi.isBusy()) {
            resendTeamWhenFree = false;
            JSONObject team = currentTeam();
            if (team != null) client.sendDigimon(team);
        }
        if (battleUi != null) battleUi.setNearNpc(nearRanking);
        if (tournamentUi != null) tournamentUi.setNearNpc(nearReception);
        // La PC abre el Laboratorio (equipo: puestos 1 y 2) al LLEGAR, no en cada frame.
        if (nearPc && !wasNearPc) LabWindow.open();
        wasNearPc = nearPc;
    }

    /** En un torneo: una franja abajo recuerda esperar el combate en la sala (salir = quedar fuera). */
    private void drawTournamentStatus(GraphicsContext g) {
        String line = tournamentUi == null ? null : tournamentUi.statusLine();
        if (line == null) return;
        double h = 26, y = canvas.getHeight() - h;
        g.setFill(Color.rgb(20, 16, 6, 0.82));
        g.fillRect(0, y, canvas.getWidth(), h);
        g.setFill(Color.rgb(240, 200, 90));
        g.fillRect(0, y, canvas.getWidth(), 2);
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 13));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("★ " + line, canvas.getWidth() / 2, y + 18);
    }

    /** Lo llama el programa principal: el campeón de un torneo recibe el premio (modo "arena" = los 2 del equipo). */
    public static void setOnTournamentPrize(Consumer<String> listener) {
        onTournamentPrize = listener == null ? mode -> { } : listener;
    }

    /** Toda la sala en pequeño (esquina superior derecha): jugadores como puntos y el recuadro de la cámara. */
    private void drawMinimap(GraphicsContext g, double viewW, double viewH) {
        double s = MINIMAP_WIDTH / LobbyMap.WIDTH;
        double w = MINIMAP_WIDTH, h = LobbyMap.HEIGHT * s;
        double x0 = canvas.getWidth() - w - 10, y0 = 10;
        g.setGlobalAlpha(0.85);
        g.setImageSmoothing(true);
        g.drawImage(mapImage, x0, y0, w, h);
        g.setImageSmoothing(false);
        g.setGlobalAlpha(1);
        g.setStroke(Color.rgb(90, 200, 250));
        g.setLineWidth(1);
        g.strokeRect(x0, y0, w, h);
        for (Map.Entry<Integer, double[]> e : drawPos.entrySet()) {
            boolean mine = e.getKey() == myId;
            g.setFill(mine ? Color.rgb(90, 220, 255) : Color.WHITE);
            double r = mine ? 3 : 2;
            g.fillOval(x0 + e.getValue()[0] * s - r, y0 + e.getValue()[1] * s - r, r * 2, r * 2);
        }
        g.setStroke(Color.rgb(255, 255, 255, 0.8));
        g.strokeRect(x0 + cameraX * s, y0 + cameraY * s, viewW * s, viewH * s);
    }

    /**
     * Posición en el instante {@code time} según las fotos recibidas
     * {tiempo, x, y}: lineal entre las dos que lo rodean; si es más nueva que
     * todas, la última (nunca adivina hacia adelante).
     */
    private static double[] interpolate(java.util.ArrayDeque<double[]> history, long time) {
        if (history.isEmpty()) return null;
        double[] prev = null;
        for (double[] s : history) {
            if (s[0] >= time) {
                if (prev == null) return new double[]{s[1], s[2]};
                double span = s[0] - prev[0];
                double f = span <= 0 ? 1 : (time - prev[0]) / span;
                return new double[]{prev[1] + (s[1] - prev[1]) * f, prev[2] + (s[2] - prev[2]) * f};
            }
            prev = s;
        }
        double[] last = history.peekLast();
        return new double[]{last[1], last[2]};
    }

    /** Al píxel de pantalla más cercano (un píxel del mundo = ZOOM píxeles de pantalla). */
    static double snap(double worldValue) {
        return Math.round(worldValue * ZOOM) / ZOOM;
    }

    private static double clamp(double v, double min, double max) {
        return max < min ? (min + max) / 2 : Math.max(min, Math.min(max, v));
    }

    /**
     * ARENA 2 vs 2 online (pedido del usuario): {@code before} se llama antes de
     * abrir la pelea con un "empezar" (el programa principal hace cruzar a tu
     * puesto 2 por su portal y luego empieza); {@code after}, al cerrarla, con el
     * resultado (true / false, null si no terminó) y un "ya volvió": el programa
     * principal hace volver a tu puesto 2 por su portal y lo llama al terminar;
     * recién entonces tu Digimon y el del rival hacen su pose en la sala.
     */
    public void setArenaHooks(Consumer<Runnable> before, BiConsumer<Boolean, Runnable> after) {
        this.arenaBefore = before;
        this.arenaAfter = after;
        if (battleUi != null) battleUi.setArenaHooks(before, after);
    }

    private Consumer<Runnable> arenaBefore;
    private BiConsumer<Boolean, Runnable> arenaAfter;

    /**
     * 0.0.4: por qué no puede pelear ahora (p. ej. "está lesionado"), según el
     * modo ("libre", "original", "arena"; null = ponerse disponible); null = puede.
     * Se revisa al ponerse disponible, al retar y al aceptar un reto.
     */
    public void setBattleBlocker(Function<String, String> blocker) {
        this.battleBlocker = blocker;
        if (battleUi != null) battleUi.setBattleBlocker(blocker);
    }

    private Function<String, String> battleBlocker = mode -> null;

    /** Cada Batalla Oficial: el programa principal la suma al récord del Digimon y la comenta. */
    public void setOnBattleResult(Consumer<OfficialBattleResult> listener) {
        this.onBattleResult = listener;
        if (battleUi != null) battleUi.setOnResult(listener);
    }

    /**
     * Hacia dónde mira un jugador y si está caminando, deducidos de cuánto se
     * movió en este frame (el servidor solo manda posiciones). Solo
     * izquierda/derecha (decisión del usuario): al ir derecho arriba o abajo
     * conserva hacia dónde miraba.
     */
    private static final class WalkAnim {
        private static final double MOVING_THRESHOLD = 0.05;
        /**
         * El dibujo alcanza la posición del servidor un instante antes de que
         * llegue la siguiente foto: sin este margen el Digimon "parpadearía" a
         * quieto 10 veces por segundo mientras camina.
         */
        private static final long STOP_GRACE_NANOS = 150_000_000L;

        boolean facingRight = false;
        boolean walking = false;
        private long lastMove = -1;

        void update(double dx, double dy, long now) {
            if (Math.hypot(dx, dy) < MOVING_THRESHOLD) {
                if (walking && now - lastMove < STOP_GRACE_NANOS) return; // sigue el paso un instante
                walking = false;
                return;
            }
            lastMove = now;
            if (Math.abs(dx) > MOVING_THRESHOLD * 0.5) facingRight = dx > 0;
            walking = true;
        }
    }
}
