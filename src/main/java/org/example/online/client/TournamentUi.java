package org.example.online.client;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import org.example.online.Protocol;
import org.example.sound.Sfx;
import org.example.sound.Sounds;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * TORNEOS en la sala (protocolo v12, pedido del usuario del 2026-10-18). Al llegar al NPC "Recepción de torneos" se
 * abre su diálogo: los torneos que hay (inscritos, solicitudes y llaves), crear uno (modo y cupos), inscribirse,
 * y para el organizador ACEPTAR / RECHAZAR a cada uno, EMPEZAR y CANCELAR. Antes de cada combate aparece, esté donde
 * esté en la sala, el aviso "¿LISTO?" con su cuenta regresiva. El servidor decide todo; esto solo muestra y pide.
 */
final class TournamentUi {

    private static final String PANEL_STYLE = "-fx-background-color: rgba(12,22,44,0.96); -fx-background-radius: 10;"
            + " -fx-border-color: #f0b428; -fx-border-radius: 10; -fx-border-width: 2;";
    private static final String CARD_STYLE = "-fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 6;";
    private static final String TEXT_STYLE = "-fx-text-fill: white; -fx-font-size: 12px;";
    private static final String DIM_STYLE = "-fx-text-fill: #aab3bc; -fx-font-size: 11px;";
    private static final String BUTTON_STYLE = "-fx-background-color: #1f6fb2; -fx-text-fill: white; -fx-font-weight: bold;"
            + " -fx-background-radius: 6; -fx-cursor: hand;";
    private static final String SELECTED_STYLE = BUTTON_STYLE + " -fx-background-color: #f0b428; -fx-text-fill: #1a1a1a;";
    private static final String GREEN_STYLE = BUTTON_STYLE + " -fx-background-color: #1f9a5a;";
    private static final String RED_STYLE = BUTTON_STYLE + " -fx-background-color: #b23a3a;";

    /** Capa encima del mapa (deja pasar los clics al mapa fuera de sus ventanas). */
    final StackPane layer = new StackPane();

    private final Supplier<VsClient> client;
    private final Supplier<String> myName;
    private final Consumer<String> notify;
    private Consumer<String> onPrize = mode -> { };

    private JSONObject state;
    private VBox dialog;
    private final VBox content = new VBox(8);
    private boolean near;

    private String createMode = Protocol.MODE_ORIGINAL;
    private int createSize = 8;
    private final CheckBox createPlay = new CheckBox("Yo también juego");

    private VBox readyBox;
    private Timeline readyCountdown;

    TournamentUi(Supplier<VsClient> client, Supplier<String> myName, Consumer<String> notify) {
        this.client = client;
        this.myName = myName;
        this.notify = notify;
        layer.setPickOnBounds(false);
        createPlay.setSelected(true);
        createPlay.setStyle(TEXT_STYLE);
    }

    /** El campeón recibe su premio: el programa principal lo guarda en su Digimon (modo: "arena" = los dos del equipo). */
    void setOnPrize(Consumer<String> onPrize) {
        this.onPrize = onPrize == null ? mode -> { } : onPrize;
    }

    /** Lo llama la sala en cada cuadro: el diálogo se abre al LLEGAR al NPC y se cierra al alejarse. */
    void setNearNpc(boolean nowNear) {
        if (nowNear && !near) openDialog();
        if (!nowNear && near) closeDialog();
        near = nowNear;
    }

    /** Mensajes de torneo; devuelve false si no es de este tema. */
    boolean handle(JSONObject m) {
        switch (m.optString("t")) {
            case "tUpdate" -> {
                state = m;
                if (dialog != null && layer.getChildren().contains(dialog)) fill();
            }
            case "tNotice" -> {
                if (m.optBoolean("sound")) Sounds.play(Sfx.AVISO);
                notify.accept("* " + m.optString("text"));
            }
            case "tReadyAsk" -> showReady(m);
            case "tPrize" -> {
                Sounds.play(Sfx.VICTORIA);
                notify.accept("* ¡Ganaste el " + m.optString("tournament") + "! PREMIO: tu Digimon puede lucirse al DOBLE"
                        + " de tamaño (se prende y se apaga desde su menú).");
                onPrize.accept(m.optString("mode"));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Empezó una pelea (del torneo o no): el aviso de LISTO ya no hace falta. */
    void battleStarted() {
        closeReady();
    }

    // ---------------------------------------------------------------- estado propio

    /** El torneo (no terminado) en que sigo en carrera, o null. */
    JSONObject myActiveTournament() {
        if (state == null) return null;
        for (Object o : state.optJSONArray("tournaments")) {
            JSONObject t = (JSONObject) o;
            if ("terminado".equals(t.optString("state"))) continue;
            for (Object p : t.optJSONArray("players")) {
                JSONObject e = (JSONObject) p;
                if (e.optString("name").equalsIgnoreCase(myName.get()) && !e.optBoolean("out")) return t;
            }
        }
        return null;
    }

    /** Aviso antes de cerrar la sala (null = puede salir sin perder nada). */
    String leaveWarning() {
        JSONObject t = myActiveTournament();
        if (t == null) return null;
        return "en curso".equals(t.optString("state"))
                ? "Estás jugando el " + t.optString("label") + ". Si sales de la sala quedas DESCALIFICADO de ese torneo"
                + " (solo de ese). ¿Salir igual?"
                : "Estás inscrito en el " + t.optString("label") + ". Si sales de la sala, te retiras de ese torneo. ¿Salir igual?";
    }

    /** Al salir confirmando: aviso al servidor (así no espera el minuto de reconexión). */
    void leaveActive() {
        JSONObject t = myActiveTournament();
        if (t != null) client.get().tournamentLeave(t.optInt("id"));
    }

    /** Por qué no puede cambiar su equipo ahora (null = puede). */
    String teamChangeBlocker() {
        JSONObject t = myActiveTournament();
        return t == null ? null : "Estás en el " + t.optString("label") + ": tu equipo no puede cambiar hasta que termine"
                + " (o te retires en la Recepción de torneos).";
    }

    /** Una línea para mostrar en la sala mientras estoy en un torneo (null = no estoy en ninguno). */
    String statusLine() {
        JSONObject t = myActiveTournament();
        if (t == null) return null;
        return "en curso".equals(t.optString("state"))
                ? "EN TORNEO (" + Protocol.modeName(t.optString("mode")) + "): espera tu combate en la sala. Si sales, quedas fuera."
                : "INSCRITO en el torneo de " + t.optString("organizer") + ": espera a que empiece. No salgas de la sala.";
    }

    // ---------------------------------------------------------------- diálogo del NPC

    private void openDialog() {
        if (dialog == null) {
            Label title = new Label("RECEPCIÓN DE TORNEOS");
            title.setStyle("-fx-text-fill: #f0c85a; -fx-font-size: 14px; -fx-font-weight: bold;");
            ScrollPane scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
            scroll.setPrefViewportHeight(380);
            Button close = button("Cerrar", BUTTON_STYLE, this::closeDialog);
            dialog = new VBox(8, title, scroll, close);
            dialog.setPadding(new Insets(12));
            dialog.setMaxWidth(420);
            dialog.setMaxHeight(Region.USE_PREF_SIZE);
            dialog.setStyle(PANEL_STYLE);
            StackPane.setAlignment(dialog, Pos.TOP_CENTER);
            StackPane.setMargin(dialog, new Insets(70, 0, 0, 0));
        }
        fill();
        if (!layer.getChildren().contains(dialog)) layer.getChildren().add(0, dialog);
    }

    private void closeDialog() {
        if (dialog != null) layer.getChildren().remove(dialog);
    }

    /** Vuelve a armar el contenido con el último estado del servidor. */
    private void fill() {
        content.getChildren().clear();
        if (state == null) {
            content.getChildren().add(text("Este servidor no tiene torneos (es de una versión anterior a la v12).", DIM_STYLE));
            return;
        }
        JSONArray list = state.optJSONArray("tournaments");
        int active = 0;
        for (Object o : list) if (!"terminado".equals(((JSONObject) o).optString("state"))) active++;
        int max = state.optInt("max", 2);
        content.getChildren().add(text("Torneos en marcha: " + active + " de " + max + " posibles a la vez. Mientras estás en"
                + " uno, solo peleas sus combates; los demás pelean libres.", DIM_STYLE));

        String me = myName.get();
        boolean involved = false, organizing = false;
        for (Object o : list) {
            JSONObject t = (JSONObject) o;
            boolean finished = "terminado".equals(t.optString("state"));
            if (!finished && t.optString("organizer").equalsIgnoreCase(me)) organizing = true;
            if (!finished && (contains(t.optJSONArray("pending"), me, false) || contains(t.optJSONArray("players"), me, true))) {
                involved = true;
            }
        }
        for (Object o : list) content.getChildren().add(card((JSONObject) o, me, involved));
        if (list.isEmpty()) content.getChildren().add(text("No hay torneos ahora. ¡Crea uno!", TEXT_STYLE));
        if (!involved && !organizing && active < max) content.getChildren().add(createCard());

        JSONArray champions = state.optJSONArray("champions");
        if (champions != null && !champions.isEmpty()) {
            StringBuilder sb = new StringBuilder("Campeones recientes:");
            for (Object o : champions) {
                JSONObject c = (JSONObject) o;
                sb.append("\n  ★ ").append(c.optString("name")).append(" con ").append(c.optString("species"))
                        .append(" — ").append(Protocol.modeName(c.optString("mode"))).append(" (").append(c.optInt("players")).append(" jugadores)");
            }
            content.getChildren().add(text(sb.toString(), "-fx-text-fill: #f0c85a; -fx-font-size: 11px;"));
        }
    }

    private VBox card(JSONObject t, String me, boolean meInvolved) {
        int id = t.optInt("id");
        String st = t.optString("state");
        JSONArray players = t.optJSONArray("players"), pending = t.optJSONArray("pending");
        boolean organizer = t.optString("organizer").equalsIgnoreCase(me);
        boolean iPlay = contains(players, me, true), iWait = contains(pending, me, false);
        String head = t.optString("label") + "  ·  " + players.length() + "/" + t.optInt("size") + " inscritos  ·  "
                + switch (st) {
                    case "inscripcion" -> "INSCRIPCIÓN ABIERTA";
                    case "en curso" -> "EN CURSO";
                    default -> "TERMINADO" + (t.optString("champion").isEmpty() ? "" : " — campeón: " + t.optString("champion"));
                };
        VBox card = new VBox(5, text(head, TEXT_STYLE + " -fx-font-weight: bold;"));
        card.setPadding(new Insets(8));
        card.setStyle(CARD_STYLE);
        card.getChildren().add(text("Inscritos: " + names(players), DIM_STYLE));
        if (!"inscripcion".equals(st)) card.getChildren().add(text(bracket(t), "-fx-text-fill: #cfe3ff; -fx-font-size: 11px;"));

        if ("inscripcion".equals(st)) {
            if (organizer) {
                for (Object o : pending) {
                    JSONObject p = (JSONObject) o;
                    String who = p.optString("name");
                    HBox row = new HBox(6, text(who + " — " + p.optString("species") + (p.optBoolean("copy") ? " (copia)" : ""), TEXT_STYLE),
                            grow(), button("ACEPTAR", GREEN_STYLE, () -> client.get().tournamentAnswer(id, who, true)),
                            button("RECHAZAR", RED_STYLE, () -> client.get().tournamentAnswer(id, who, false)));
                    row.setAlignment(Pos.CENTER_LEFT);
                    card.getChildren().add(row);
                }
                if (pending.isEmpty()) card.getChildren().add(text("Sin solicitudes nuevas.", DIM_STYLE));
                Button start = button("EMPEZAR (" + players.length() + ")", GREEN_STYLE, () -> client.get().tournamentStart(id));
                start.setDisable(players.length() < 2);
                card.getChildren().add(new HBox(6, start, button("CANCELAR TORNEO", RED_STYLE, () -> client.get().tournamentCancel(id))));
            }
            if (iPlay && !organizer) {
                card.getChildren().add(new HBox(6, text("Estás inscrito: espera a que empiece en la sala.", TEXT_STYLE),
                        grow(), button("RETIRARME", RED_STYLE, () -> client.get().tournamentLeave(id))));
            } else if (iPlay) {
                card.getChildren().add(button("NO JUGAR (solo organizar)", BUTTON_STYLE, () -> client.get().tournamentLeave(id)));
            } else if (iWait) {
                card.getChildren().add(new HBox(6, text("Esperando que " + t.optString("organizer") + " te acepte...", TEXT_STYLE),
                        grow(), button("CANCELAR", BUTTON_STYLE, () -> client.get().tournamentLeave(id))));
            } else if (!meInvolved && players.length() < t.optInt("size")) {
                card.getChildren().add(button("INSCRIBIRME", GREEN_STYLE, () -> client.get().tournamentJoin(id)));
            }
        } else if ("en curso".equals(st)) {
            if (contains(players, me, true)) {
                card.getChildren().add(button("RETIRARME (quedo fuera de este torneo)", RED_STYLE, () -> client.get().tournamentLeave(id)));
            }
            if (organizer) card.getChildren().add(button("CANCELAR TORNEO", RED_STYLE, () -> client.get().tournamentCancel(id)));
        }
        return card;
    }

    private VBox createCard() {
        Label title = text("CREAR UN TORNEO", "-fx-text-fill: #f0c85a; -fx-font-size: 12px; -fx-font-weight: bold;");
        HBox modes = new HBox(6);
        for (String mode : new String[]{Protocol.MODE_ORIGINAL, Protocol.MODE_FREE, Protocol.MODE_ARENA}) {
            Button b = button(Protocol.modeName(mode).replace("Batalla ", ""), mode.equals(createMode) ? SELECTED_STYLE : BUTTON_STYLE,
                    () -> { createMode = mode; fill(); });
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
            modes.getChildren().add(b);
        }
        HBox sizes = new HBox(6, text("Cupos:", TEXT_STYLE));
        sizes.setAlignment(Pos.CENTER_LEFT);
        for (int size : Protocol.TOURNAMENT_SIZES) {
            sizes.getChildren().add(button(String.valueOf(size), size == createSize ? SELECTED_STYLE : BUTTON_STYLE,
                    () -> { createSize = size; fill(); }));
        }
        String how = switch (createMode) {
            case Protocol.MODE_ARENA -> "ARENA 2 vs 2: los puestos 1 y 2 de cada uno (Child o superiores).";
            case Protocol.MODE_FREE -> "Libre: stats + el bono de los puntos (tope 120). Pelea el puesto 1.";
            default -> "Original: solo los stats de la DIM. Pelea el puesto 1.";
        };
        Label rules = text(how + " Tú aceptas a mano a cada inscrito y lo empiezas cuando quieras. Eliminación directa;"
                + " antes de cada combate los dos dicen LISTO; empate = revancha y al tercero, sorteo. El campeón gana"
                + " lucir a su Digimon al doble.", DIM_STYLE);
        Button create = button("CREAR TORNEO", GREEN_STYLE, () -> client.get().tournamentCreate(createMode, createSize, createPlay.isSelected()));
        VBox box = new VBox(6, title, modes, sizes, createPlay, rules, create);
        box.setPadding(new Insets(8));
        box.setStyle(CARD_STYLE);
        return box;
    }

    // ---------------------------------------------------------------- ¿LISTO?

    private void showReady(JSONObject m) {
        closeReady();
        Sounds.play(Sfx.AVISO);
        Label label = text(m.optString("tournament") + " — " + m.optString("round") + ":\ntu combate contra "
                + m.optString("opponent") + " (" + m.optString("species") + (m.optBoolean("copy") ? ", copia" : "") + ").\n¿Estás LISTO?",
                TEXT_STYLE + " -fx-font-size: 13px;");
        label.setAlignment(Pos.CENTER);
        Label timer = text("", "-fx-text-fill: #f0c85a; -fx-font-size: 20px; -fx-font-weight: bold;");
        Label waiting = text("Si no respondes a tiempo, pierdes este combate.", DIM_STYLE);
        Button ready = button("¡LISTO!", GREEN_STYLE + " -fx-font-size: 14px;", () -> { });
        ready.setOnAction(e -> {
            client.get().tournamentReady();
            ready.setDisable(true);
            waiting.setText("Esperando a " + m.optString("opponent") + "...");
        });
        readyBox = new VBox(10, label, timer, ready, waiting);
        readyBox.setAlignment(Pos.CENTER);
        readyBox.setPadding(new Insets(16));
        readyBox.setMaxWidth(380);
        readyBox.setMaxHeight(Region.USE_PREF_SIZE);
        readyBox.setStyle(PANEL_STYLE);
        layer.getChildren().add(readyBox);
        int[] remaining = {m.optInt("seconds", 60)};
        timer.setText(String.valueOf(remaining[0]));
        readyCountdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remaining[0]--;
            timer.setText(String.valueOf(Math.max(0, remaining[0])));
            if (remaining[0] <= 0) closeReady(); // el servidor decide y avisa
        }));
        readyCountdown.setCycleCount(remaining[0]);
        readyCountdown.play();
    }

    private void closeReady() {
        if (readyCountdown != null) readyCountdown.stop();
        if (readyBox != null) layer.getChildren().remove(readyBox);
        readyBox = null;
    }

    // ---------------------------------------------------------------- auxiliares

    private static String bracket(JSONObject t) {
        List<String> lines = new ArrayList<>();
        for (Object r : t.optJSONArray("rounds")) {
            JSONObject round = (JSONObject) r;
            StringBuilder sb = new StringBuilder(round.optString("name").toUpperCase() + ":");
            for (Object mo : round.optJSONArray("matches")) {
                JSONObject m = (JSONObject) mo;
                String b = m.optString("b");
                sb.append("\n   ").append(m.optString("a")).append(b.isEmpty() ? "" : " vs " + b);
                String w = m.optString("winner");
                if (!w.isEmpty()) sb.append("  →  ").append(w);
                else if (!b.isEmpty()) sb.append(switch (m.optString("state")) {
                    case "ready" -> "  (diciendo LISTO)";
                    case "fighting" -> "  (peleando)";
                    case "rematch" -> "  (revancha)";
                    default -> "  (esperando)";
                });
                if (!m.optString("note").isEmpty()) sb.append("  — ").append(m.optString("note"));
            }
            lines.add(sb.toString());
        }
        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    private static String names(JSONArray players) {
        if (players == null || players.isEmpty()) return "nadie todavía";
        List<String> n = new ArrayList<>();
        for (Object o : players) {
            JSONObject p = (JSONObject) o;
            n.add(p.optString("name") + (p.optBoolean("copy") ? " (copia)" : "") + (p.optBoolean("out") ? " ✗"
                    : p.optBoolean("away") ? " (fuera de la sala)" : ""));
        }
        return String.join(", ", n);
    }

    private static boolean contains(JSONArray list, String name, boolean onlyIn) {
        if (list == null || name == null) return false;
        for (Object o : list) {
            JSONObject p = (JSONObject) o;
            if (p.optString("name").equalsIgnoreCase(name) && !(onlyIn && p.optBoolean("out"))) return true;
        }
        return false;
    }

    private static Label text(String s, String style) {
        Label l = new Label(s);
        l.setStyle(style);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    private static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Button button(String text, String style, Runnable action) {
        Button b = new Button(text);
        b.setStyle(style);
        b.setOnAction(e -> action.run());
        return b;
    }
}
