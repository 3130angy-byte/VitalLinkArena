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
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import org.example.animation.DimSpriteSet;
import org.example.animation.SpriteRole;
import org.example.animation.VPetStageTier;
import org.example.battle.AttackSpriteResolver;
import org.example.battle.BattleEngine;
import org.example.battle.BattlePresentationScreen;
import org.example.online.Protocol;
import org.example.sound.Sfx;
import org.example.sound.Sounds;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Interfaz de las Batallas Oficiales dentro de la sala (flujo decidido por
 * el usuario):
 *  - al acercarte al NPC "Batalla oficial" se abre su diálogo: ponerte
 *    disponible (o no) y la lista de jugadores disponibles;
 *  - arriba eliges el MODO: Batalla Libre (con el bono de tus puntos) o
 *    Batalla Original (stats de la DIM, sin bono);
 *  - clic en un nombre = retarlo en ese modo: tú ves "Esperando respuesta..." y él ve
 *    "[nombre] te ha retado a una batalla, ¿aceptar?", ambos con un
 *    contador de 15 s (sin respuesta = rechazo automático, lo decide el
 *    servidor);
 *  - si acepta, la sala se cambia por el coliseo con la pelea que calculó
 *    el SERVIDOR (cada uno la ve desde su lado: él a la izquierda); al
 *    terminar, vuelve la sala.
 */
final class OfficialBattleUi {

    private static final String PANEL_STYLE = "-fx-background-color: rgba(12,22,44,0.96); -fx-background-radius: 10;"
            + " -fx-border-color: #5ab8ff; -fx-border-radius: 10; -fx-border-width: 2;";
    private static final String TEXT_STYLE = "-fx-text-fill: white; -fx-font-size: 12px;";
    private static final String BUTTON_STYLE = "-fx-background-color: #1f6fb2; -fx-text-fill: white; -fx-font-weight: bold;"
            + " -fx-background-radius: 6; -fx-cursor: hand;";
    private static final String SELECTED_STYLE = BUTTON_STYLE + " -fx-background-color: #f0b428; -fx-text-fill: #1a1a1a;";

    /** Capa encima del mapa donde van los diálogos (deja pasar los clics al mapa fuera de ellos). */
    final StackPane layer = new StackPane();

    private final Supplier<VsClient> client;
    private final Map<Integer, LobbyDigimon> digimons;
    private final IntSupplier myId;
    private final BorderPane root;
    private final Node lobbyCenter;
    private final double battleWidth, battleHeight;
    private final Consumer<String> notify;
    private Consumer<OfficialBattleResult> onResult;

    private VBox npcBox;
    private final VBox listBox = new VBox(4);
    private final Label npcInfo = new Label();
    private final Label npcError = new Label();
    private final Button availabilityButton = new Button();
    private final Button freeModeButton = new Button("BATALLA LIBRE");
    private final Button originalModeButton = new Button("BATALLA ORIGINAL");
    private final Button arenaModeButton = new Button("ARENA 2 VS 2");
    /** ARENA 2 vs 2 online en curso (null si no hay). */
    private OnlineArenaSession arena;
    private final Label modeInfo = new Label();
    private String mode = Protocol.MODE_FREE;
    private Timeline listRefresh;
    private boolean available = false;
    private boolean near = false;

    private VBox modal;
    private Timeline countdown;

    OfficialBattleUi(Supplier<VsClient> client, Map<Integer, LobbyDigimon> digimons, IntSupplier myId,
                     BorderPane root, Node lobbyCenter, double battleWidth, double battleHeight,
                     Consumer<String> notify) {
        this.client = client;
        this.digimons = digimons;
        this.myId = myId;
        this.root = root;
        this.lobbyCenter = lobbyCenter;
        this.battleWidth = battleWidth;
        this.battleHeight = battleHeight;
        this.notify = notify;
        layer.setPickOnBounds(false);
    }

    /**
     * ARENA online: antes de abrir su pantalla (recibe "empezar") y al cerrarla (recibe el resultado
     * -- true, false o null si no terminó -- y "ya volvió": tu puesto 2 vuelve por su portal y recién
     * entonces se hace la pose en la sala). Ver LobbyWindow.setArenaHooks.
     */
    private Consumer<Runnable> arenaBefore;
    private BiConsumer<Boolean, Runnable> arenaAfter;

    void setArenaHooks(Consumer<Runnable> before, BiConsumer<Boolean, Runnable> after) {
        this.arenaBefore = before;
        this.arenaAfter = after;
    }

    /** Cada Batalla Oficial (para que el Digimon la comente y la sume a su récord de Vital Values). */
    void setOnResult(Consumer<OfficialBattleResult> onResult) {
        this.onResult = onResult;
    }

    /** Por qué no puede pelear ahora según el modo (null = sí puede); ver LobbyWindow.setBattleBlocker. */
    private Function<String, String> battleBlocker = mode -> null;

    void setBattleBlocker(Function<String, String> blocker) {
        this.battleBlocker = blocker == null ? mode -> null : blocker;
    }

    /** Si no puede pelear, lo avisa en el chat de la sala y devuelve true. */
    private boolean blocked(String mode) {
        String why = battleBlocker.apply(mode);
        if (why == null) return false;
        notify.accept("* " + why);
        return true;
    }

    /** Lo llama la sala en cada frame: el diálogo se abre al LLEGAR al NPC y se cierra al alejarse. */
    void setNearNpc(boolean nowNear) {
        if (nowNear && !near) openNpcDialog();
        if (!nowNear && near) closeNpcDialog();
        near = nowNear;
    }

    /** Mensajes de Batallas Oficiales; devuelve false si el mensaje no es de este tema. */
    boolean handle(JSONObject m) {
        switch (m.optString("t")) {
            case "availability" -> {
                available = m.optBoolean("on");
                npcError.setText(m.optString("reason", ""));
                refreshNpcTexts();
            }
            case "availableList" -> fillList(m.optJSONArray("players"));
            case "challengePending" -> showModal(Protocol.modeName(m.optString("mode")) + ": esperando respuesta de "
                    + m.optString("name") + "...",
                    m.optInt("seconds", Protocol.CHALLENGE_SECONDS));
            case "challenged" -> {
                int from = m.optInt("from");
                String challengeMode = m.optString("mode");
                Button yes = button("Sí", () -> {
                    client.get().replyChallenge(from, !blocked(challengeMode));
                    closeModal();
                });
                Button no = button("No", () -> { client.get().replyChallenge(from, false); closeModal(); });
                Sounds.play(Sfx.AVISO); // "WARNING": alguien te reta
                showModal(m.optString("name") + " (" + m.optString("species") + copyMark(m) + ", " + LobbyDigimon.rankText(m.optString("rank"))
                        + ") te ha retado a una " + Protocol.modeName(m.optString("mode")) + ", ¿aceptar?", m.optInt("seconds", Protocol.CHALLENGE_SECONDS), yes, no);
            }
            case "challengeEnded" -> {
                closeModal();
                notify.accept("* " + m.optString("reason"));
            }
            case "battle" -> playBattle(m);
            case "arenaStart" -> {
                closeModal();
                closeNpcDialog();
                // La sesión vive hasta el "arenaEnd" aunque se cierre su ventana (el resultado igual se anota).
                arena = new OnlineArenaSession(client, digimons, myId.getAsInt(), notify, onResult, arenaBefore, arenaAfter,
                        this::reactInLobby);
                arena.handle(m);
            }
            case "arenaTurn", "arenaAttack", "arenaDefend", "arenaWait", "arenaSwitch", "arenaHit", "arenaEnd" -> {
                if (arena != null) arena.handle(m);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- diálogo del NPC

    private void openNpcDialog() {
        if (npcBox == null) {
            Label title = new Label("BATALLA OFICIAL");
            title.setStyle("-fx-text-fill: #8fd6ff; -fx-font-size: 14px; -fx-font-weight: bold;");
            npcInfo.setStyle(TEXT_STYLE);
            npcInfo.setWrapText(true);
            npcError.setStyle("-fx-text-fill: #ff8a8a; -fx-font-size: 11px;");
            npcError.setWrapText(true);
            availabilityButton.setStyle(BUTTON_STYLE);
            availabilityButton.setOnAction(e -> {
                if (!available && blocked(null)) return;
                client.get().setAvailable(!available);
            });
            freeModeButton.setOnAction(e -> selectMode(Protocol.MODE_FREE));
            originalModeButton.setOnAction(e -> selectMode(Protocol.MODE_ORIGINAL));
            arenaModeButton.setOnAction(e -> selectMode(Protocol.MODE_ARENA));
            arenaModeButton.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(arenaModeButton, Priority.ALWAYS);
            freeModeButton.setMaxWidth(Double.MAX_VALUE);
            originalModeButton.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(freeModeButton, Priority.ALWAYS);
            HBox.setHgrow(originalModeButton, Priority.ALWAYS);
            modeInfo.setStyle("-fx-text-fill: #aab3bc; -fx-font-size: 11px;");
            modeInfo.setWrapText(true);
            Label listTitle = new Label("Jugadores disponibles (clic para retar):");
            listTitle.setStyle(TEXT_STYLE + " -fx-font-weight: bold;");
            Button close = button("Cerrar", this::closeNpcDialog);
            Button refresh = button("Actualizar", () -> client.get().listAvailable());
            npcBox = new VBox(8, title, npcInfo, availabilityButton, npcError,
                    new HBox(6, freeModeButton, originalModeButton), arenaModeButton, modeInfo, listTitle, listBox, new HBox(8, refresh, close));
            npcBox.setPadding(new Insets(12));
            npcBox.setMaxWidth(330);
            npcBox.setMaxHeight(Region.USE_PREF_SIZE);
            npcBox.setStyle(PANEL_STYLE);
            StackPane.setAlignment(npcBox, Pos.TOP_CENTER);
            StackPane.setMargin(npcBox, new Insets(110, 0, 0, 0));
        }
        refreshNpcTexts();
        if (!layer.getChildren().contains(npcBox)) layer.getChildren().add(npcBox);
        client.get().listAvailable();
        // Mientras el diálogo está abierto, la lista se refresca sola cada 3 s.
        if (listRefresh != null) listRefresh.stop();
        listRefresh = new Timeline(new KeyFrame(Duration.seconds(3), e -> client.get().listAvailable()));
        listRefresh.setCycleCount(Animation.INDEFINITE);
        listRefresh.play();
    }

    private void closeNpcDialog() {
        if (listRefresh != null) listRefresh.stop();
        if (npcBox != null) layer.getChildren().remove(npcBox);
    }

    /** El modo elegido se usa al hacer clic en un jugador de la lista. */
    private void selectMode(String newMode) {
        mode = newMode;
        refreshNpcTexts();
    }

    private void refreshNpcTexts() {
        npcInfo.setText(available
                ? "Estás disponible: otros jugadores pueden retarte."
                : "¿Quieres quedar disponible para una Batalla Oficial?");
        availabilityButton.setText(available ? "Dejar de estar disponible" : "Ponerme disponible");
        freeModeButton.setStyle(Protocol.MODE_FREE.equals(mode) ? SELECTED_STYLE : BUTTON_STYLE);
        originalModeButton.setStyle(Protocol.MODE_ORIGINAL.equals(mode) ? SELECTED_STYLE : BUTTON_STYLE);
        arenaModeButton.setStyle(Protocol.MODE_ARENA.equals(mode) ? SELECTED_STYLE : BUTTON_STYLE);
        modeInfo.setText(switch (mode) {
            case Protocol.MODE_ORIGINAL -> "Original: solo los stats de la DIM, sin bono. Pelea tu puesto 1.";
            case Protocol.MODE_ARENA -> "ARENA 2 vs 2: tus puestos 1 y 2 contra los del rival, con números y defensa. "
                    + "Los 2 deben ser Child o superior (se eligen en la PC). No cuenta para el récord.";
            default -> "Libre: tus stats + el bono de tus puntos (cada 10 = +50% DP, +25% HP, +1 AP; tope 120). Pelea tu puesto 1.";
        });
    }

    private void fillList(JSONArray players) {
        listBox.getChildren().clear();
        if (players == null || players.length() == 0) {
            Label empty = new Label("Nadie más está disponible por ahora.");
            empty.setStyle("-fx-text-fill: #aab3bc; -fx-font-size: 11px;");
            listBox.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.getJSONObject(i);
            int id = p.getInt("id");
            Button b = button(p.optString("name") + "  —  " + p.optString("species") + copyMark(p) + "  [" + LobbyDigimon.rankText(p.optString("rank")) + "]",
                    () -> {
                        if (!blocked(mode)) client.get().challenge(id, mode);
                    });
            b.setMaxWidth(Double.MAX_VALUE);
            listBox.getChildren().add(b);
        }
    }

    // ---------------------------------------------------------------- ventanas de reto

    private void showModal(String text, int seconds, Button... buttons) {
        closeModal();
        Label label = new Label();
        label.setStyle(TEXT_STYLE + " -fx-font-size: 13px;");
        label.setWrapText(true);
        Label timer = new Label();
        timer.setStyle("-fx-text-fill: #8fd6ff; -fx-font-size: 20px; -fx-font-weight: bold;");
        HBox row = new HBox(10, buttons);
        row.setAlignment(Pos.CENTER);
        modal = new VBox(10, label, timer, row);
        modal.setAlignment(Pos.CENTER);
        modal.setPadding(new Insets(16));
        modal.setMaxWidth(360);
        modal.setMaxHeight(Region.USE_PREF_SIZE);
        modal.setStyle(PANEL_STYLE);
        label.setText(text);
        layer.getChildren().add(modal);

        int[] remaining = {seconds};
        timer.setText(String.valueOf(remaining[0]));
        countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remaining[0]--;
            timer.setText(String.valueOf(Math.max(0, remaining[0])));
            // A los 0 s se cierra; el servidor decide el rechazo y avisa con "challengeEnded".
            if (remaining[0] <= 0) closeModal();
        }));
        countdown.setCycleCount(seconds);
        countdown.play();
    }

    /** Hay un reto pendiente o una pelea en curso: el servidor no deja cambiar el equipo. */
    boolean isBusy() {
        return modal != null || root.getCenter() != lobbyCenter || (arena != null && !arena.isOver());
    }

    private void closeModal() {
        if (countdown != null) countdown.stop();
        if (modal != null) layer.getChildren().remove(modal);
        modal = null;
    }

    // ---------------------------------------------------------------- la pelea

    /**
     * Reproduce la pelea que calculó el servidor, desde MI lado: yo a la
     * izquierda (PLAYER). Nunca se recalcula nada aquí (regla del proyecto:
     * RoundOutcome es la única fuente de verdad).
     */
    private void playBattle(JSONObject m) {
        closeModal();
        closeNpcDialog();
        int me = myId.getAsInt();
        JSONObject a = m.getJSONObject("a"), b = m.getJSONObject("b");
        boolean iAmA = a.getInt("id") == me;
        JSONObject mine = iAmA ? a : b, rival = iAmA ? b : a;
        LobbyDigimon myDigimon = digimons.get(mine.getInt("id"));
        LobbyDigimon rivalDigimon = digimons.get(rival.getInt("id"));
        if (myDigimon == null || rivalDigimon == null) {
            notify.accept("* No se pudo mostrar la batalla (faltan los datos de un Digimon).");
            client.get().battleDone();
            return;
        }

        BattleEngine.Combatant player = combatant(mine);
        BattleEngine.Combatant enemy = combatant(rival);
        List<BattleEngine.RoundOutcome> rounds = new ArrayList<>();
        JSONArray arr = m.getJSONArray("rounds");
        int lastMine = player.hp, lastRival = enemy.hp;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.getJSONObject(i);
            boolean byMe = "a".equals(r.optString("attacker")) == iAmA;
            lastMine = iAmA ? r.getInt("hpA") : r.getInt("hpB");
            lastRival = iAmA ? r.getInt("hpB") : r.getInt("hpA");
            rounds.add(new BattleEngine.RoundOutcome(r.getInt("round"),
                    byMe ? BattleEngine.Side.PLAYER : BattleEngine.Side.ENEMY,
                    BattleEngine.AttackType.valueOf(r.getString("type")), r.getInt("attackId"), r.getInt("damage"),
                    lastMine, lastRival));
        }
        int winner = m.getInt("winner");
        boolean draw = winner == 0;
        boolean won = winner == me;
        BattleEngine.BattleResult result = new BattleEngine.BattleResult(won, draw, rounds.size(),
                lastMine, lastRival, rounds, 0, 0);

        BattlePresentationScreen screen = new BattlePresentationScreen(new AttackSpriteResolver());
        Node node = screen.buildNode(myDigimon.fullFrames[Protocol.FRAME_IDLE_1], rivalDigimon.fullFrames[Protocol.FRAME_IDLE_1],
                myDigimon.nameImage, rivalDigimon.nameImage, player, enemy, battleWidth, battleHeight);
        screen.setBig(myDigimon.big, rivalDigimon.big); // premio de campeón: al doble en pixel art
        root.setCenter(node);
        String outcome = draw ? "DRAW" : won ? "WIN" : "LOSS";
        // Se avisa YA, no al terminar la animación: cerrar la sala a media pelea no borra una derrota.
        if (onResult != null) onResult.accept(new OfficialBattleResult(outcome, rivalDigimon.stage, m.optString("mode")));
        // Tras un pulso de layout (la presentación mide posiciones de los sprites en pantalla).
        int rivalId = rival.getInt("id");
        Platform.runLater(() -> screen.play(result, spriteSet(myDigimon), spriteSet(rivalDigimon), player, enemy, () -> {
            screen.dispose();
            root.setCenter(lobbyCenter);
            client.get().battleDone();
            notify.accept("* " + m.optString("tournament", Protocol.modeName(m.optString("mode"))) + ": " + (draw ? "empate." : won ? "¡ganaste!" : "perdiste."));
            if (!draw) reactInLobby(rivalId, won);
        }));
    }

    /**
     * De vuelta en la sala (pedido del usuario, 2026-10-05): recién aquí tu Digimon y el del
     * rival hacen su pose de victoria o derrota, con el sonido de TU resultado (en la pantalla
     * de la pelea ya no suena). Los demás jugadores no saben el resultado: para ellos, nada.
     */
    void reactInLobby(int rivalId, boolean won) {
        LobbyDigimon mine = digimons.get(myId.getAsInt()), rival = digimons.get(rivalId);
        if (mine != null) mine.react(won);
        if (rival != null) rival.react(!won);
        Sounds.play(won ? Sfx.VICTORIA : Sfx.DERROTA);
    }

    /** Solo lo que la animación necesita: HP máximo, atributo y ataque pequeño (nunca DP/AP del rival). */
    private static BattleEngine.Combatant combatant(JSONObject side) {
        return new BattleEngine.Combatant(0, side.getInt("maxHp"), 0, side.getInt("attribute"),
                side.getInt("small"), -1, 2);
    }

    private static DimSpriteSet spriteSet(LobbyDigimon d) {
        Image idle = d.fullFrames[Protocol.FRAME_IDLE_1];
        DimSpriteSet set = new DimSpriteSet(VPetStageTier.CHILD_PLUS, (int) idle.getWidth(), (int) idle.getHeight());
        set.put(SpriteRole.IDLE_1, idle);
        set.put(SpriteRole.IDLE_2, d.fullFrames[Protocol.FRAME_IDLE_2]);
        set.put(SpriteRole.WALK_1, d.fullFrames[Protocol.FRAME_WALK_1]);
        set.put(SpriteRole.WALK_2, d.fullFrames[Protocol.FRAME_WALK_2]);
        set.put(SpriteRole.ATTACK, d.fullFrames[Protocol.FRAME_ATTACK]);
        set.put(SpriteRole.DODGE, d.fullFrames[Protocol.FRAME_DODGE]);
        return set;
    }

    /** Protocolo v11: " (copia)" si el Digimon de ese jugador es una copia del LAB. */
    private static String copyMark(JSONObject m) {
        return m.optBoolean("copy", false) ? " (copia)" : "";
    }

    private static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setStyle(BUTTON_STYLE);
        b.setOnAction(e -> action.run());
        return b;
    }

}
