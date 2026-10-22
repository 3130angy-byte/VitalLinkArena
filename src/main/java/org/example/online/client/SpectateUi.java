package org.example.online.client;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import org.example.battle.AttackSpriteResolver;
import org.example.battle.BattleEngine;
import org.example.battle.BattlePresentationScreen;
import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pantalla "COMBATES EN VIVO" de la sala (protocolo v13, conjunto 3 de la actualización conjunta): al llegar a la
 * pantalla del muro norte se abre la lista de las peleas que están ocurriendo; VER = mirarla.
 *  - Batalla Original / Libre: en el coliseo de la misma ventana, DESDE EL PRINCIPIO (el servidor la calculó entera),
 *    con el que retó a la izquierda. Al final, "GANA <jugador>" o "EMPATE" (no es tu pelea: ni ¡VICTORIA! ni pose).
 *  - ARENA 2 vs 2: EN VIVO, en su ventana de siempre, sin botones; cerrarla = dejar de mirar.
 * Si te toca pelear a ti (un reto, tu combate de torneo), se deja de mirar al instante.
 */
final class SpectateUi {

    /** Capa encima del mapa para el diálogo (deja pasar los clics al mapa fuera de él). */
    final StackPane layer = new StackPane();

    private final Supplier<VsClient> client;
    private final Map<Integer, LobbyDigimon> digimons;
    private final BorderPane root;
    private final Node lobbyCenter;
    private final double battleWidth, battleHeight;
    private final Consumer<String> notify;

    private VBox box;
    private final VBox listBox = new VBox(4);
    private Timeline refresh;
    private boolean near;

    /** Mirando un 1 vs 1 (en el centro de la ventana) o una ARENA (en su ventana). */
    private BattlePresentationScreen watching;
    private OnlineArenaSession watchingArena;
    private int arenaFight = -1;

    SpectateUi(Supplier<VsClient> client, Map<Integer, LobbyDigimon> digimons, BorderPane root, Node lobbyCenter,
               double battleWidth, double battleHeight, Consumer<String> notify) {
        this.client = client;
        this.digimons = digimons;
        this.root = root;
        this.lobbyCenter = lobbyCenter;
        this.battleWidth = battleWidth;
        this.battleHeight = battleHeight;
        this.notify = notify;
        layer.setPickOnBounds(false);
    }

    /** Lo llama la sala en cada cuadro: el diálogo se abre al LLEGAR a la pantalla y se cierra al alejarse. */
    void setNearScreen(boolean nowNear) {
        if (nowNear && !near) open();
        if (!nowNear && near) close();
        near = nowNear;
    }

    /** Mensajes "spec*"; false si no son de este tema. */
    boolean handle(JSONObject m) {
        switch (m.optString("t")) {
            case "specList" -> fill(m.optJSONArray("fights"));
            case "specBattle" -> playBattle(m);
            case "specArena" -> arenaMessage(m);
            case "specEnded" -> notify.accept("* " + m.optString("reason"));
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Te toca pelear (o el aviso de LISTO de tu torneo): se deja de mirar sin más. */
    void stopForOwnFight() {
        if (watching != null) {
            watching.dispose();
            watching = null;
            root.setCenter(lobbyCenter); // si llega tu pelea, ella misma ocupa el centro
        }
        if (watchingArena != null) {
            OnlineArenaSession s = watchingArena;
            watchingArena = null;
            arenaFight = -1;
            s.closeSpectator();
        }
        close();
    }

    // ---------------------------------------------------------------- lista

    private void open() {
        if (box == null) {
            Label title = new Label("COMBATES EN VIVO");
            title.setStyle(OfficialBattleUi.TEXT_STYLE + " -fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #ffd246;");
            Label info = new Label("Las peleas que están ocurriendo en la sala. Original y Libre se ven desde el"
                    + " principio; la ARENA, en vivo.");
            info.setStyle("-fx-text-fill: #aab3bc; -fx-font-size: 11px;");
            info.setWrapText(true);
            Button close = button("Cerrar", this::close);
            box = new VBox(8, title, info, listBox, close);
            box.setPadding(new Insets(12));
            box.setMaxWidth(380);
            box.setMaxHeight(Region.USE_PREF_SIZE);
            box.setStyle(OfficialBattleUi.PANEL_STYLE);
            StackPane.setAlignment(box, Pos.TOP_CENTER);
            StackPane.setMargin(box, new Insets(110, 0, 0, 0));
        }
        if (!layer.getChildren().contains(box)) layer.getChildren().add(box);
        client.get().spectateList();
        if (refresh != null) refresh.stop();
        refresh = new Timeline(new KeyFrame(Duration.seconds(3), e -> client.get().spectateList()));
        refresh.setCycleCount(Animation.INDEFINITE);
        refresh.play();
    }

    private void close() {
        if (refresh != null) refresh.stop();
        if (box != null) layer.getChildren().remove(box);
    }

    private void fill(JSONArray fights) {
        listBox.getChildren().clear();
        if (fights == null || fights.length() == 0) {
            Label empty = new Label("No hay peleas en este momento.");
            empty.setStyle("-fx-text-fill: #aab3bc; -fx-font-size: 11px;");
            listBox.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < fights.length(); i++) {
            JSONObject f = fights.getJSONObject(i);
            int id = f.getInt("id");
            String kind = f.optString("tournament", "").isEmpty() ? Protocol.modeName(f.optString("mode"))
                    : f.optString("tournament");
            long s = f.optLong("seconds");
            Label text = new Label(who(f.getJSONObject("a")) + "  vs  " + who(f.getJSONObject("b")) + "\n" + kind
                    + "  ·  " + (s / 60) + ":" + String.format("%02d", s % 60)
                    + (f.optInt("watchers") > 0 ? "  ·  mirando: " + f.optInt("watchers") : ""));
            text.setStyle(OfficialBattleUi.TEXT_STYLE);
            text.setWrapText(true);
            text.setMaxWidth(270);
            Button watch = button("VER", () -> {
                close();
                client.get().spectateWatch(id);
            });
            HBox row = new HBox(8, text, watch);
            row.setAlignment(Pos.CENTER_LEFT);
            listBox.getChildren().add(row);
        }
    }

    private static String who(JSONObject side) {
        return side.optString("name") + " (" + side.optString("species") + (side.optBoolean("copy") ? ", copia" : "") + ")";
    }

    // ---------------------------------------------------------------- Original / Libre

    private void playBattle(JSONObject spec) {
        if (watching != null || watchingArena != null) return; // ya miras otra
        JSONObject m = spec.getJSONObject("battle");
        JSONObject a = m.getJSONObject("a"), b = m.getJSONObject("b");
        LobbyDigimon left = digimons.get(a.getInt("id")), right = digimons.get(b.getInt("id"));
        if (left == null || right == null) {
            notify.accept("* No se pudo mostrar esa pelea (faltan los datos de un Digimon).");
            return;
        }
        BattleEngine.Combatant player = OfficialBattleUi.combatant(a);
        BattleEngine.Combatant enemy = OfficialBattleUi.combatant(b);
        List<BattleEngine.RoundOutcome> rounds = new ArrayList<>();
        JSONArray arr = m.getJSONArray("rounds");
        int hpA = player.hp, hpB = enemy.hp;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.getJSONObject(i);
            hpA = r.getInt("hpA");
            hpB = r.getInt("hpB");
            rounds.add(new BattleEngine.RoundOutcome(r.getInt("round"),
                    "a".equals(r.optString("attacker")) ? BattleEngine.Side.PLAYER : BattleEngine.Side.ENEMY,
                    BattleEngine.AttackType.valueOf(r.getString("type")), r.getInt("attackId"), r.getInt("damage"),
                    hpA, hpB));
        }
        int winner = m.getInt("winner");
        BattleEngine.BattleResult result = new BattleEngine.BattleResult(winner == a.getInt("id"), winner == 0,
                rounds.size(), hpA, hpB, rounds, 0, 0);

        String nameA = spec.getJSONObject("a").optString("name"), nameB = spec.getJSONObject("b").optString("name");
        BattlePresentationScreen screen = new BattlePresentationScreen(new AttackSpriteResolver());
        Node node = screen.buildNode(left.fullFrames[Protocol.FRAME_IDLE_1], right.fullFrames[Protocol.FRAME_IDLE_1],
                left.nameImage, right.nameImage, player, enemy, battleWidth, battleHeight);
        screen.setBig(left.big, right.big);
        String winnerName = winner == 0 ? null : winner == a.getInt("id") ? nameA : nameB;
        screen.setEndText(winnerName == null ? "EMPATE" : "GANA " + shortName(winnerName));
        Button stop = button("DEJAR DE VER", this::stopWatchingBattle);
        Label caption = new Label("Mirando: " + nameA + " vs " + nameB + "  ·  "
                + m.optString("tournament", Protocol.modeName(m.optString("mode"))));
        caption.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-background-color: rgba(0,0,0,0.55);"
                + " -fx-padding: 4 8 4 8; -fx-background-radius: 6;");
        StackPane holder = new StackPane(node, caption, stop);
        StackPane.setAlignment(caption, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(stop, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(caption, new Insets(8));
        StackPane.setMargin(stop, new Insets(8));
        watching = screen;
        root.setCenter(holder);
        Platform.runLater(() -> screen.play(result, OfficialBattleUi.spriteSet(left), OfficialBattleUi.spriteSet(right),
                player, enemy, () -> {
                    if (watching != screen) return;
                    stopWatchingBattle();
                    notify.accept("* " + (winnerName == null ? "Terminó en empate." : "Ganó " + winnerName + "."));
                }));
    }

    private void stopWatchingBattle() {
        if (watching == null) return;
        watching.dispose();
        watching = null;
        root.setCenter(lobbyCenter);
    }

    /** El texto grande del coliseo cabe en 480 px de ancho: nombres largos, recortados. */
    private static String shortName(String name) {
        String n = name.toUpperCase();
        return n.length() > 14 ? n.substring(0, 14) : n;
    }

    // ---------------------------------------------------------------- ARENA en vivo

    private void arenaMessage(JSONObject spec) {
        int fight = spec.optInt("fight", -1);
        JSONObject m = spec.optJSONObject("m");
        if (m == null) return;
        if ("arenaStart".equals(m.optString("t"))) {
            if (watching != null || watchingArena != null) return;
            JSONObject names = spec.optJSONObject("names");
            String nameA = names == null ? "?" : names.optString("a"), nameB = names == null ? "?" : names.optString("b");
            arenaFight = fight;
            OnlineArenaSession[] self = new OnlineArenaSession[1];
            self[0] = OnlineArenaSession.spectator(client, digimons, notify, nameA, nameB, () -> {
                if (watchingArena == self[0]) {
                    watchingArena = null;
                    arenaFight = -1;
                }
            });
            watchingArena = self[0];
        }
        if (watchingArena != null && fight == arenaFight) watchingArena.handle(m);
    }

    boolean isWatching() {
        return watching != null || watchingArena != null;
    }

    private static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setStyle(OfficialBattleUi.BUTTON_STYLE);
        b.setOnAction(e -> action.run());
        return b;
    }
}
