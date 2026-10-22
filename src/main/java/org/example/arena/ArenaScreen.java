package org.example.arena;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.example.battle.AttackSpriteResolver;
import org.example.battle.BattlePresentationScreen;
import org.example.sound.Sfx;
import org.example.sound.Sounds;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Pantalla de la ARENA (2 vs 2). La pantalla solo pregunta al jugador
 * (acción, combo, defensa) y anima lo que devuelve ArenaEngine; nunca calcula
 * daño por su cuenta.
 *
 * Dos diseños (arena.properties, pantalla.estilo):
 *  - "celular" (por defecto, pedido del usuario a partir de capturas de la app
 *    Vital Bracelet Arena): SIN fondo de ciudad (oscuro liso con líneas de
 *    neón donde se paran los Digimon), ventana casi cuadrada, rival arriba a la
 *    derecha y tú abajo a la izquierda, placas blancas inclinadas con insignia
 *    de atributo, reloj circular, botones ATTACK / W-ATTACK / CHANGE.
 *  - "coliseo": el diseño anterior, sobre el fondo del coliseo.
 * El arte de la app es de Bandai y NO se usa: todo son figuras propias que
 * imitan la disposición y los colores.
 *
 * Animaciones: el Digimon EXPULSA su ataque (BIG con combo de 10+ o en el
 * W-ATTACK, si no SMALL; sprites del firmware), impacto con destello, temblor,
 * número de daño, barra de vida que cae al instante con un tramo rojo de
 * "daño reciente", escena especial negra con líneas amarillas para el
 * W-ATTACK (atacan los dos), pantalla GUTS!!!, caída, cambio y victoria.
 */
public final class ArenaScreen {

    // Mismas fracciones del coliseo que BattlePresentationScreen (PNG real 1671x941).
    private static final double PIVOT_Y = 246.0 / 270.0;
    private static final double ARENA_RY = 66.0 / 270.0;
    private static final double PLAYER_X = 0.5 - 0.16;
    private static final double CPU_X = 0.5 + 0.16;
    private static final double FIGHTER_Y = PIVOT_Y - ARENA_RY * 0.85;
    private static final double SCREEN_W = 246.0 / 1671, SCREEN_H = 150.0 / 941;
    private static final double LEFT_SCREEN_X = 182.0 / 1671, RIGHT_SCREEN_X = 1489.0 / 1671, SCREEN_Y = 209.0 / 941;

    private static final String BUTTON = "-fx-background-color: #1f6fb2; -fx-text-fill: white; -fx-font-weight: bold;"
            + " -fx-font-size: 14px; -fx-background-radius: 8; -fx-padding: 8 18 8 18; -fx-cursor: hand;";
    private static final String W_BUTTON = "-fx-background-color: #f0b428; -fx-text-fill: #1a1a1a; -fx-font-weight: bold;"
            + " -fx-font-size: 14px; -fx-background-radius: 8; -fx-padding: 8 18 8 18; -fx-cursor: hand;";

    // Colores del diseño celular (propios, parecidos a la app)
    private static final Color YELLOW = Color.web("#ffe03a");
    private static final Color ORANGE = Color.web("#ffb21e");
    private static final Color GREEN = Color.web("#2fe07a");
    private static final Color HP_GREEN = Color.web("#2ed573");
    private static final Color HP_RED = Color.web("#ff3b3b");
    private static final Color INK = Color.web("#141a33");
    private static final Color CYAN = Color.web("#5fe3ff");

    /** Segundos que tarda el proyectil del small/big attack en llegar al rival. */
    private static final double PROJECTILE_SECONDS = 0.45;
    /** Los sprites de ataque del firmware miran a la IZQUIERDA (igual que en BattlePresentationScreen). */
    private static final boolean EFFECT_FACES_RIGHT = false;
    /** Contador de combo de 10 círculos (como en la app): llenos = BIG ATTACK. */
    private static final int COUNTER_DOTS = 10;

    private final ArenaEngine engine;
    private final Random random = new Random();
    private final Stage stage = new Stage();
    private final Pane root = new Pane();
    private final double width, height;
    /** true = diseño "celular" (estilo VB Arena); false = coliseo. */
    private final boolean phone;
    /** Aumento de los sprites DIM (entero, para que el pixel art no se deforme). */
    private final double spriteScale;
    private final AttackSpriteResolver attackSprites = new AttackSpriteResolver();

    private final ImageView[] views = {new ImageView(), new ImageView()};
    /** Qué Digimon (0/1) se VE en cada lado: el motor ya puede haber cambiado (caída) antes de que termine la animación. */
    private final int[] spriteIndex = {0, 0};
    private final boolean[] busy = {false, false};
    private final Label message = new Label();
    private final Label bigText = new Label();
    private final Label subText = new Label();
    private final Pane minigameLayer = new Pane();
    private final Pane overlayLayer = new Pane();
    private final ImageView impactView = new ImageView();
    private Rectangle flash;
    private Timeline idleTimeline;

    // --- diseño coliseo
    private final Pane[] huds = new Pane[2];
    private final HBox actions = new HBox(12);
    private final Button attackButton = new Button("ATACAR");
    private final Button switchButton = new Button("CAMBIAR");
    private final Button wButton = new Button("W-ATTACK");

    // --- diseño celular
    private final Pane phoneButtons = new Pane();
    private Pane attackBtn, wBtn, changeBtn, partnerCard;
    private final Rectangle[] wSegments = new Rectangle[5];
    /** Tarjetas del compañero (junto a CHANGE y junto a PROTECT): retrato y vida. */
    private final List<ImageView> partnerViews = new ArrayList<>();
    private final List<Rectangle> partnerFills = new ArrayList<>();
    private double partnerHpW;
    /** Al ser atacado (como la app): DEFENSE = minijuego de la barra; PROTECT = el compañero recibe el golpe. */
    private final Pane phoneDefense = new Pane();
    private Pane defenseBtn, protectBtn;
    private final HBox defenseActions = new HBox(12);
    private final Button defenseButton = new Button("DEFENSE");
    private final Button protectButton = new Button("PROTECT");
    private Consumer<Boolean> pendingDefense;
    /** Placa forzada a mostrar a este Digimon (el compañero que PROTEGE), -1 = la normal. */
    private final int[] forcedPlate = {-1, -1};
    private final Pane[] plates = new Pane[2];
    private final Rectangle[] plateFill = new Rectangle[2], plateRecent = new Rectangle[2];
    private final double[] plateBarW = new double[2];
    private final Label[] plateHp = new Label[2];
    private final int[] plateActive = {-1, -1};
    private final double[] plateShownHp = {-1, -1};
    /** Mientras cae un Digimon, su placa sigue mostrándolo (no salta al compañero antes de tiempo). */
    private final boolean[] holdPanel = {false, false};
    private Arc clockArc;
    private final Map<Image, Image> darkNames = new HashMap<>();

    /** Defensa en curso: se detiene con clic, STOP o ESPACIO. */
    private Runnable stopDefense;
    private boolean finished = false;
    private Boolean result; // null = abandonó

    public ArenaScreen(ArenaEngine engine) {
        this.engine = engine;
        this.phone = engine.config().phoneLayout;
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        if (phone) {
            // Casi cuadrada (pedido del usuario: que no quede tan rectangular como el coliseo).
            double h = Math.min(screen.getHeight() * 0.86, screen.getWidth() * 0.9);
            this.width = Math.round(h * 0.92);
            this.height = Math.round(h);
            this.spriteScale = Math.max(2, Math.floor(h * 0.19 / 56));
        } else {
            double h = screen.getHeight() * 0.62;
            double w = h * BattlePresentationScreen.BG_ASPECT;
            if (w > screen.getWidth() * 0.95) {
                w = screen.getWidth() * 0.95;
                h = w / BattlePresentationScreen.BG_ASPECT;
            }
            this.width = w;
            this.height = h;
            this.spriteScale = 1;
        }
    }

    /** Abre la pelea; {@code onFinished} recibe true (ganaste), false (perdiste) o null (cerraste la ventana). */
    public void open(Consumer<Boolean> onFinished) {
        show(onFinished, "ARENA");
        say("¡Comienza la ARENA! " + engine.active(ArenaEngine.PLAYER).name + " sale primero.");
        showBig("BATTLE START!");
        Sounds.play(Sfx.INICIO_BATALLA);
        pause(1.4, this::startPlayerTurn);
    }

    private void show(Consumer<Boolean> onFinished, String title) {
        build();
        stage.setTitle(title);
        stage.setResizable(false);
        stage.setOnHidden(e -> {
            if (idleTimeline != null) idleTimeline.stop();
            onFinished.accept(result);
        });
        stage.show();
    }

    // ---------------------------------------------------------------- modo ONLINE
    // El servidor decide turnos y daño (OnlineArenaMatch); esta pantalla solo pregunta
    // al jugador (acción, combo, defensa) y anima lo que llega. El motor es un espejo:
    // su estado se copia del servidor (ArenaEngine.applyState).

    /** Elección pendiente del jugador en el modo online ("attack", "switch", "w"). */
    private Consumer<String> pendingChoice;

    /** Abre la pantalla sin la lógica contra la máquina. */
    public void openOnline(String title, Consumer<Boolean> onFinished) {
        show(onFinished, title);
        Sounds.play(Sfx.INICIO_BATALLA);
    }

    /** Tu turno: muestra ATTACK / W-ATTACK / CHANGE y entrega lo elegido. */
    public void promptAction(boolean canSwitch, boolean wReady, Consumer<String> choose) {
        refreshAll();
        setDisabled(switchButton, changeBtn, !canSwitch);
        setDisabled(wButton, wBtn, !wReady);
        say("Tu turno: " + engine.active(ArenaEngine.PLAYER).name + ". Elige qué hacer.");
        pendingChoice = action -> {
            pendingChoice = null;
            showActions(false);
            choose.accept(action);
        };
        showActions(true);
    }

    /** El servidor pidió tu combo: minijuego de números (con el tiempo que dio el servidor). */
    public void playAttack(double seconds, IntConsumer done) {
        say("¡Toca los números en orden y encadena el COMBO!");
        attackMinigame(seconds, done);
    }

    public void playAttack(IntConsumer done) {
        playAttack(engine.config().comboSeconds, done);
    }

    /**
     * El servidor pidió tu defensa: primero eliges DEFENSE (minijuego de la
     * barra) o PROTECT (tu compañero recibe el golpe). {@code done} recibe
     * (protect, distancia al centro).
     */
    public void playDefense(String text, boolean canProtect, java.util.function.BiConsumer<Boolean, Double> done) {
        say(text);
        defenseChoice(canProtect, protect -> {
            if (protect) done.accept(true, 1.0);
            else pause(0.2, () -> defenseMinigame(d -> done.accept(false, d)));
        });
    }

    /** Anima un golpe ya resuelto por el servidor (el estado ya se copió al espejo). */
    public void playHit(ArenaEngine.Hit hit, Runnable done) {
        animateHit(hit, done);
    }

    /** Anima un cambio de Digimon ya resuelto por el servidor. */
    public void playSwitch(int side, Runnable done) {
        swapIn(side, () -> {
            say(side == ArenaEngine.PLAYER ? "¡Adelante, " + engine.active(side).name + "!" : "El rival cambia de Digimon.");
            pause(0.6, done);
        });
    }

    /** Final dictado por el servidor (también si el rival se fue de la sala). */
    public void showEnd(boolean won, String reason) {
        if (finished) return;
        finished = true;
        result = won;
        refreshAll();
        showActions(false);
        pendingDefense = null;
        phoneDefense.setVisible(false);
        defenseActions.setVisible(false);
        minigameLayer.getChildren().clear();
        stopDefense = null;
        stopClock();
        // Solo el texto: la pose y el sonido de victoria o derrota llegan al volver (pedido del usuario).
        showBig(won ? "WIN!!" : "LOSE...");
        say(reason != null ? reason : won ? "¡Tu equipo ganó la ARENA!" : "Tu equipo perdió esta vez.");
        addExitButton();
    }

    /**
     * Final para quien MIRA la ARENA desde la pantalla de la sala (v13): "FIN" y quién ganó, sin WIN/LOSE (no es
     * su pelea).
     */
    public void showEndText(String big, String text) {
        if (finished) return;
        finished = true;
        refreshAll();
        showActions(false);
        minigameLayer.getChildren().clear();
        stopClock();
        endHold = true;
        showBig(big);
        say(text);
        addExitButton();
    }

    /** El texto grande del final se queda en pantalla. */
    private boolean endHold;

    public void refresh() {
        refreshAll();
    }

    public void sayText(String text) {
        say(text);
    }

    public void close() {
        stage.close();
    }

    // ---------------------------------------------------------------- armado

    private void build() {
        root.setPrefSize(width, height);
        root.setClip(new Rectangle(width, height)); // nada se sale de la ventana (líneas del W-ATTACK, temblores)
        if (phone) {
            // Sin fondo de ciudad (pedido del usuario): degradado oscuro y algunas estelas de luz.
            root.setStyle("-fx-background-color: linear-gradient(to bottom, #08102a 0%, #0f1f4a 55%, #08102a 100%);");
            Random deco = new Random(7);
            for (int i = 0; i < 9; i++) {
                double y = height * (0.05 + deco.nextDouble() * 0.55);
                Rectangle streak = new Rectangle(deco.nextDouble() * width * 0.6, y, width * (0.15 + deco.nextDouble() * 0.35), 2);
                streak.setFill(i % 3 == 0 ? Color.web("#c040ff", 0.25) : Color.web("#5fe3ff", 0.18));
                root.getChildren().add(streak);
            }
            for (int side = 0; side < 2; side++) root.getChildren().add(neonFloor(side));
        } else {
            root.setStyle("-fx-background-color: #101018;");
            ImageView bg = new ImageView(load("/Battle coliseum.png"));
            bg.setFitWidth(width);
            bg.setFitHeight(height);
            root.getChildren().add(bg);
        }

        for (int side = 0; side < 2; side++) {
            ImageView v = views[side];
            v.setSmooth(false);
            org.example.animation.PixelScale.crisp(v); // píxeles duplicados de verdad (setSmooth no basta en todos los equipos)
            v.setScaleX(side == ArenaEngine.PLAYER ? -1 : 1); // convención: mirar a la derecha = -1
            placeFighter(side);
            root.getChildren().add(v);
            if (!phone) {
                huds[side] = new Pane();
                root.getChildren().add(huds[side]);
            }
        }
        impactView.setImage(load("/attacks/impact.png"));
        impactView.setSmooth(false);
        impactView.setVisible(false);
        impactView.setMouseTransparent(true);
        root.getChildren().add(impactView);

        message.setTextFill(Color.WHITE);
        message.setFont(Font.font("Consolas", FontWeight.BOLD, phone ? 13 : 15));
        message.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 6; -fx-padding: 3 10 3 10;");
        message.setLayoutY(phone ? height * 0.625 : height * 0.03);
        message.layoutXProperty().bind(root.widthProperty().subtract(message.widthProperty()).divide(2));
        message.setMouseTransparent(true);
        root.getChildren().add(message);

        if (phone) {
            buildPhoneControls();
        } else {
            attackButton.setStyle(BUTTON);
            switchButton.setStyle(BUTTON);
            wButton.setStyle(W_BUTTON);
            attackButton.setOnAction(e -> onAttackPressed());
            wButton.setOnAction(e -> onWPressed());
            switchButton.setOnAction(e -> onChangePressed());
            actions.getChildren().addAll(attackButton, switchButton, wButton);
            actions.setAlignment(Pos.CENTER);
            actions.setVisible(false);
            actions.layoutXProperty().bind(root.widthProperty().subtract(actions.widthProperty()).divide(2));
            actions.setLayoutY(height * 0.86);
            root.getChildren().add(actions);
            defenseButton.setStyle(BUTTON);
            protectButton.setStyle(BUTTON.replace("#1f6fb2", "#1f9a5a"));
            defenseButton.setOnAction(e -> { if (pendingDefense != null) pendingDefense.accept(false); });
            protectButton.setOnAction(e -> { if (pendingDefense != null) pendingDefense.accept(true); });
            defenseActions.getChildren().addAll(defenseButton, protectButton);
            defenseActions.setAlignment(Pos.CENTER);
            defenseActions.setVisible(false);
            defenseActions.layoutXProperty().bind(root.widthProperty().subtract(defenseActions.widthProperty()).divide(2));
            defenseActions.setLayoutY(height * 0.86);
            root.getChildren().add(defenseActions);
        }

        bigText.setFont(Font.font("Arial Black", FontWeight.BOLD, phone ? 38 : 34));
        bigText.setTextFill(YELLOW);
        bigText.setStyle("-fx-effect: dropshadow(gaussian, #1a1030, 6, 0.9, 0, 2);");
        bigText.setVisible(false);
        bigText.setMouseTransparent(true);
        bigText.layoutXProperty().bind(root.widthProperty().subtract(bigText.widthProperty()).divide(2));
        bigText.setLayoutY(phone ? height * 0.27 : height * 0.36);
        subText.setFont(Font.font("Arial Black", FontWeight.BOLD, phone ? 20 : 18));
        subText.setTextFill(Color.WHITE);
        subText.setStyle("-fx-effect: dropshadow(gaussian, #1a1030, 5, 0.9, 0, 1);");
        subText.setVisible(false);
        subText.setMouseTransparent(true);
        subText.layoutXProperty().bind(root.widthProperty().subtract(subText.widthProperty()).divide(2));
        subText.setLayoutY(phone ? height * 0.35 : height * 0.45);

        flash = new Rectangle(0, 0, width, height);
        flash.setFill(Color.WHITE);
        flash.setOpacity(0);
        flash.setMouseTransparent(true);

        minigameLayer.setPrefSize(width, height);
        minigameLayer.setPickOnBounds(false);
        overlayLayer.setPrefSize(width, height);
        overlayLayer.setPickOnBounds(false);
        overlayLayer.setMouseTransparent(true);
        root.getChildren().addAll(flash, minigameLayer, bigText, subText, overlayLayer);

        refreshAll();
        Scene scene = new Scene(root, width, height, Color.BLACK);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE && stopDefense != null) stopDefense.run();
        });
        stage.setScene(scene);

        idleTimeline = new Timeline(new KeyFrame(Duration.seconds(0.5), e -> {
            for (int side = 0; side < 2; side++) {
                if (busy[side]) continue;
                ArenaFighter f = engine.fighter(side, spriteIndex[side]);
                views[side].setImage(views[side].getImage() == f.idle1 ? f.idle2 : f.idle1);
            }
        }));
        idleTimeline.setCycleCount(Animation.INDEFINITE);
        idleTimeline.play();
    }

    /** Línea de neón donde se para cada Digimon (como el piso de luz de la app). */
    private Node neonFloor(int side) {
        double[] c = fighterCenter(side);
        double y = c[1] + spriteH() / 2 - 4;
        double x0 = side == ArenaEngine.PLAYER ? 0 : width * 0.4;
        double x1 = side == ArenaEngine.PLAYER ? width * 0.62 : width;
        Rectangle line = new Rectangle(x0, y, x1 - x0, 3);
        line.setFill(Color.web("#e8f8ff"));
        line.setEffect(new DropShadow(14, Color.web("#7fd8ff")));
        Rectangle accent = new Rectangle(x0 + (x1 - x0) * 0.55, y + 3, (x1 - x0) * 0.25, 2);
        accent.setFill(Color.web("#c040ff", 0.8));
        Pane p = new Pane(line, accent);
        p.setMouseTransparent(true);
        return p;
    }

    /** Centro del Digimon de ese lado. Celular: rival arriba a la derecha, tú abajo a la izquierda. */
    private double[] fighterCenter(int side) {
        if (phone) {
            return side == ArenaEngine.PLAYER
                    ? new double[]{width * 0.27, height * 0.42}
                    : new double[]{width * 0.72, height * 0.215};
        }
        return new double[]{(side == ArenaEngine.PLAYER ? PLAYER_X : CPU_X) * width, FIGHTER_Y * height};
    }

    private double spriteW() { return 64 * spriteScale; }
    private double spriteH() { return 56 * spriteScale; }

    /** Premio del campeón de un torneo (2026-10-18): al doble, cada píxel 2x2, con los pies en el mismo lugar. */
    private static double k(ArenaFighter f) {
        return f != null && f.big ? 2 : 1;
    }

    /** Ubica una imagen de Digimon con su centro normal en c (al doble crece hacia arriba y a los lados). */
    private void placeSprite(ImageView v, ArenaFighter f, double cx, double cy) {
        double k = k(f);
        v.setFitWidth(spriteW() * k);
        v.setFitHeight(spriteH() * k);
        v.setLayoutX(cx - spriteW() * k / 2);
        v.setLayoutY(cy + spriteH() / 2 - spriteH() * k);
    }

    private void placeFighter(int side) {
        ImageView v = views[side];
        spriteIndex[side] = engine.activeIndex(side);
        v.setImage(engine.active(side).idle1);
        double[] c = fighterCenter(side);
        placeSprite(v, engine.active(side), c[0], c[1]);
        v.setTranslateX(0);
        v.setTranslateY(0);
        v.setRotate(0);
        v.setOpacity(1);
    }

    private void refreshAll() {
        for (int side = 0; side < 2; side++) {
            if (phone) drawPlate(side);
            else drawHud(side);
        }
        setDisabled(wButton, wBtn, !engine.wAttackReady(ArenaEngine.PLAYER));
        setDisabled(switchButton, changeBtn, !engine.canSwitch(ArenaEngine.PLAYER));
        if (phone) refreshPhoneButtons();
    }

    // ---------------------------------------------------------------- controles del diseño celular

    private void buildPhoneControls() {
        double x = width * 0.04, w = width * 0.62, h = height * 0.088;
        attackBtn = slantButton("ATTACK", YELLOW, x, height * 0.665, w, h, starIcon(h * 0.32, Color.WHITE));
        wBtn = slantButton("W-ATTACK", ORANGE, x, height * 0.765, w, h, starIcon(h * 0.32, Color.WHITE));
        changeBtn = slantButton("CHANGE", GREEN, x, height * 0.865, w, h, changeIcon(h * 0.3));
        // Medidor del W-ATTACK: 5 segmentos verticales, como en la app
        for (int i = 0; i < wSegments.length; i++) {
            Rectangle seg = new Rectangle(w - h * 0.62 - 6, h * (0.78 - i * 0.15), h * 0.3, h * 0.11);
            seg.setFill(Color.web("#3a3a3a"));
            seg.setStroke(INK);
            wSegments[i] = seg;
            wBtn.getChildren().add(seg);
        }
        attackBtn.setOnMouseClicked(e -> onAttackPressed());
        wBtn.setOnMouseClicked(e -> onWPressed());
        changeBtn.setOnMouseClicked(e -> onChangePressed());

        // Tarjeta del compañero (a la derecha de CHANGE): retrato + vida
        partnerCard = partnerCard(width * 0.69, height * 0.865, width * 0.27, h);

        phoneButtons.getChildren().addAll(attackBtn, wBtn, changeBtn, partnerCard);
        phoneButtons.setVisible(false);
        phoneButtons.setPickOnBounds(false);
        root.getChildren().add(phoneButtons);

        // Al ser atacado (como la app): DEFENSE (minijuego) o PROTECT (el compañero recibe el golpe)
        defenseBtn = slantButton("DEFENSE", Color.web("#3fc8f0"), x, height * 0.665, w, h, shieldIcon(h * 0.34));
        protectBtn = slantButton("PROTECT", GREEN, x, height * 0.765, w, h, changeIcon(h * 0.3));
        defenseBtn.setOnMouseClicked(e -> { if (pendingDefense != null) pendingDefense.accept(false); });
        protectBtn.setOnMouseClicked(e -> {
            if (pendingDefense != null && !protectBtn.isDisabled()) pendingDefense.accept(true);
        });
        phoneDefense.getChildren().addAll(defenseBtn, protectBtn, partnerCard(width * 0.69, height * 0.765, width * 0.27, h));
        phoneDefense.setVisible(false);
        phoneDefense.setPickOnBounds(false);
        root.getChildren().add(phoneDefense);

        // Reloj circular junto a tu placa (cuenta el tiempo del combo)
        double r = height * 0.036;
        StackPane clock = new StackPane();
        clock.setLayoutX(width * 0.6);
        clock.setLayoutY(height * 0.535 + height * 0.042 - r);
        Circle back = new Circle(r, Color.web("#0b1020"));
        back.setStroke(Color.web("#3a4466"));
        back.setStrokeWidth(2);
        clockArc = new Arc(0, 0, r - 2, r - 2, 90, 360);
        clockArc.setType(ArcType.OPEN);
        clockArc.setFill(null);
        clockArc.setStroke(YELLOW);
        clockArc.setStrokeWidth(4);
        Circle face = new Circle(r * 0.42, Color.TRANSPARENT);
        face.setStroke(YELLOW);
        face.setStrokeWidth(2);
        Line hand1 = new Line(0, 0, 0, -r * 0.3);
        hand1.setStroke(YELLOW);
        hand1.setStrokeWidth(2);
        Line hand2 = new Line(0, 0, r * 0.22, 0);
        hand2.setStroke(YELLOW);
        hand2.setStrokeWidth(2);
        Pane hands = new Pane(hand1, hand2);
        hands.setMaxSize(0, 0);
        clock.getChildren().addAll(back, clockArc, face, hands);
        clock.setMouseTransparent(true);
        root.getChildren().add(clock);
    }

    /** Tarjeta blanca con tu compañero (retrato y vida), como la de la app junto a CHANGE / PROTECT. */
    private Pane partnerCard(double x, double y, double cw, double h) {
        Pane p = new Pane();
        p.setLayoutX(x);
        p.setLayoutY(y);
        Polygon card = new Polygon(h * 0.25, 0, cw, 0, cw - h * 0.25, h, 0, h);
        card.setFill(Color.WHITE);
        card.setStroke(INK);
        ImageView view = new ImageView();
        view.setSmooth(false);
        view.setFitWidth(64 * Math.max(1, Math.floor(h / 56)));
        view.setFitHeight(56 * Math.max(1, Math.floor(h / 56)));
        view.setScaleX(-1);
        view.setLayoutX(h * 0.25);
        view.setLayoutY((h - view.getFitHeight()) / 2);
        partnerHpW = cw * 0.42;
        Rectangle hpBack = new Rectangle(cw - partnerHpW - h * 0.35, h * 0.62, partnerHpW, h * 0.14);
        hpBack.setFill(Color.web("#cfd6e0"));
        Rectangle hpFill = new Rectangle(cw - partnerHpW - h * 0.35, h * 0.62, partnerHpW, h * 0.14);
        hpFill.setFill(HP_GREEN);
        Label mate = new Label("COMPAÑERO");
        mate.setFont(Font.font("Arial", FontWeight.BOLD, Math.max(9, h * 0.15)));
        mate.setTextFill(INK);
        mate.setLayoutX(cw - partnerHpW - h * 0.35);
        mate.setLayoutY(h * 0.2);
        p.getChildren().addAll(card, view, hpBack, hpFill, mate);
        p.setMouseTransparent(true);
        partnerViews.add(view);
        partnerFills.add(hpFill);
        return p;
    }

    private static Node shieldIcon(double r) {
        Polygon s = new Polygon(-r * 0.75, -r * 0.85, r * 0.75, -r * 0.85, r * 0.75, r * 0.05, 0, r, -r * 0.75, r * 0.05);
        s.setFill(Color.web("#ffffff", 0.0));
        s.setStroke(Color.WHITE);
        s.setStrokeWidth(3);
        return s;
    }

    /** Botón inclinado de la app: barra de color con texto oscuro y un ícono a la derecha. */
    private Pane slantButton(String text, Color fill, double x, double y, double w, double h, Node icon) {
        Pane p = new Pane();
        p.setLayoutX(x);
        p.setLayoutY(y);
        Polygon shadow = new Polygon(4, 4, w + 4, 4, w - h * 0.35 + 4, h + 4, 4, h + 4);
        shadow.setFill(Color.web("#000000", 0.35));
        Polygon body = new Polygon(0, 0, w, 0, w - h * 0.35, h, 0, h);
        body.setFill(fill);
        body.setStroke(Color.WHITE);
        body.setStrokeWidth(2);
        Polygon shine = new Polygon(0, 0, w, 0, w - h * 0.12, h * 0.33, 0, h * 0.33);
        shine.setFill(Color.web("#ffffff", 0.28));
        Label l = new Label(text);
        l.setFont(Font.font("Arial Black", FontWeight.BOLD, Math.max(14, h * 0.36)));
        l.setTextFill(INK);
        l.setLayoutX(h * 0.35);
        l.setLayoutY(h * 0.2);
        icon.setLayoutX(w - h * 1.15);
        icon.setLayoutY(h * 0.5);
        p.getChildren().addAll(shadow, body, shine, l, icon);
        p.setStyle("-fx-cursor: hand;");
        p.setOnMousePressed(e -> { if (!p.isDisabled()) { p.setScaleX(0.97); p.setScaleY(0.97); } });
        p.setOnMouseReleased(e -> { p.setScaleX(1); p.setScaleY(1); });
        return p;
    }

    private static Node starIcon(double r, Color color) {
        Polygon star = star(0, 0, r, r * 0.45);
        star.setFill(Color.web("#ffffff", 0.0));
        star.setStroke(color);
        star.setStrokeWidth(3);
        return star;
    }

    private static Node changeIcon(double r) {
        Arc a = new Arc(0, 0, r, r, 30, 270);
        a.setType(ArcType.OPEN);
        a.setFill(null);
        a.setStroke(Color.WHITE);
        a.setStrokeWidth(4);
        Polygon tip = new Polygon(r * 0.9, -r * 0.7, r * 1.35, -r * 0.05, r * 0.45, -r * 0.05);
        tip.setFill(Color.WHITE);
        return new Pane(a, tip);
    }

    /** Estrella de 5 puntas con centro (cx, cy). */
    private static Polygon star(double cx, double cy, double outer, double inner) {
        Polygon p = new Polygon();
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? outer : inner;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            p.getPoints().addAll(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
        }
        return p;
    }

    private void refreshPhoneButtons() {
        double g = engine.gauge(ArenaEngine.PLAYER) / ArenaEngine.GAUGE_FULL;
        for (int i = 0; i < wSegments.length; i++) {
            boolean on = g >= (i + 1) / (double) wSegments.length - 0.001;
            wSegments[i].setFill(on ? (engine.wAttackReady(ArenaEngine.PLAYER) ? Color.web("#ff5a1e") : YELLOW) : Color.web("#3a3a3a"));
        }
        ArenaFighter mate = engine.fighter(ArenaEngine.PLAYER, 1 - engine.activeIndex(ArenaEngine.PLAYER));
        for (int i = 0; i < partnerViews.size(); i++) {
            partnerViews.get(i).setImage(mate.idle1);
            partnerViews.get(i).setOpacity(mate.fainted() ? 0.3 : 1);
            partnerFills.get(i).setWidth(partnerHpW * mate.hpFraction());
        }
    }

    private void setDisabled(Button colliseumButton, Pane phoneButton, boolean disabled) {
        colliseumButton.setDisable(disabled);
        if (phoneButton != null) {
            phoneButton.setDisable(disabled);
            phoneButton.setOpacity(disabled ? 0.45 : 1);
        }
    }

    private void showActions(boolean visible) {
        if (phone) phoneButtons.setVisible(visible);
        else actions.setVisible(visible);
    }

    /**
     * Al ser atacado (como la app): DEFENSE = minijuego de la barra; PROTECT = tu
     * compañero aparece un momento y recibe el golpe (sin minijuego); el activo
     * sigue siendo el mismo. Sin compañero en pie, PROTECT queda apagado.
     */
    private void defenseChoice(boolean canProtect, Consumer<Boolean> chosen) {
        refreshAll();
        pendingDefense = protect -> {
            pendingDefense = null;
            Sounds.play(Sfx.CONFIRMAR);
            phoneDefense.setVisible(false);
            defenseActions.setVisible(false);
            chosen.accept(protect);
        };
        protectButton.setDisable(!canProtect);
        if (protectBtn != null) {
            protectBtn.setDisable(!canProtect);
            protectBtn.setOpacity(canProtect ? 1 : 0.45);
        }
        if (phone) phoneDefense.setVisible(true);
        else defenseActions.setVisible(true);
    }

    private void onAttackPressed() {
        if (attackBtn != null && attackBtn.isDisabled()) return;
        Sounds.play(Sfx.CONFIRMAR);
        if (pendingChoice != null) pendingChoice.accept("attack");
        else playerAttack(false);
    }

    private void onWPressed() {
        if (wBtn != null && wBtn.isDisabled()) return;
        Sounds.play(Sfx.CONFIRMAR);
        if (pendingChoice != null) pendingChoice.accept("w");
        else playerAttack(true);
    }

    private void onChangePressed() {
        if (changeBtn != null && changeBtn.isDisabled()) return;
        Sounds.play(Sfx.CONFIRMAR);
        if (pendingChoice != null) pendingChoice.accept("switch");
        else playerSwitch();
    }

    // ---------------------------------------------------------------- placas (diseño celular)

    /**
     * Placa blanca inclinada (como la app): insignia de atributo (Va/Da/Vi/Fr),
     * nombre (sprite NAME oscurecido) y barra de vida. Al recibir daño la barra
     * CAE AL INSTANTE y queda un tramo rojo de "daño reciente" que se encoge.
     */
    private void drawPlate(int side) {
        boolean mine = side == ArenaEngine.PLAYER;
        int activeIndex = forcedPlate[side] >= 0 ? forcedPlate[side]
                : holdPanel[side] && plateActive[side] >= 0 ? plateActive[side] : engine.activeIndex(side);
        ArenaFighter f = engine.fighter(side, activeIndex);
        double pw = width * 0.52, ph = height * 0.082;
        double px = mine ? width * 0.04 : width * 0.44, py = mine ? height * 0.535 : height * 0.035;

        if (plateActive[side] != activeIndex || plates[side] == null) {
            plateActive[side] = activeIndex;
            if (plates[side] != null) root.getChildren().remove(plates[side]);
            double skew = ph * 0.3;
            Pane plate = new Pane();
            plate.setLayoutX(px);
            plate.setLayoutY(py);
            Polygon shadow = new Polygon(skew + 4, 4, pw + 4, 4, pw - skew + 4, ph + 4, 4, ph + 4);
            shadow.setFill(Color.web("#000000", 0.35));
            Polygon body = new Polygon(skew, 0, pw, 0, pw - skew, ph, 0, ph);
            body.setFill(Color.WHITE);
            // "cola" de globo que apunta a su Digimon
            Polygon tail = mine
                    ? new Polygon(ph * 0.5, 0, ph * 0.8, -ph * 0.28, ph * 0.95, 0)
                    : new Polygon(pw - ph * 1.3, ph, pw - ph * 1.05, ph + ph * 0.28, pw - ph * 0.85, ph);
            tail.setFill(Color.WHITE);

            double br = ph * 0.3, bx = skew * 0.6 + br + 4, by = ph * 0.5;
            Circle badge = new Circle(bx, by, br, attributeColor(f.attribute));
            badge.setStroke(Color.WHITE);
            badge.setStrokeWidth(2);
            Label code = new Label(attributeCode(f.attribute));
            code.setFont(Font.font("Arial", FontWeight.BOLD, br * 0.95));
            code.setTextFill(Color.WHITE);
            code.setLayoutX(bx - br * 0.62);
            code.setLayoutY(by - br * 0.62);

            double nx = bx + br + 8;
            Node name;
            Image dark = darkName(f.nameImage);
            if (dark != null) {
                ImageView nv = new ImageView(dark);
                nv.setSmooth(false);
                nv.setPreserveRatio(true);
                nv.setFitHeight(Math.max(8, Math.round(ph * 0.26)));
                nv.setLayoutX(nx);
                nv.setLayoutY(ph * 0.12);
                name = nv;
            } else {
                Label nl = new Label(f.name);
                nl.setFont(Font.font("Arial", FontWeight.BOLD, ph * 0.22));
                nl.setTextFill(INK);
                nl.setLayoutX(nx);
                nl.setLayoutY(ph * 0.06);
                name = nl;
            }

            double barW = pw - nx - skew - 10;
            plateBarW[side] = barW;
            Rectangle back = new Rectangle(nx, ph * 0.56, barW, ph * 0.2);
            back.setFill(Color.web("#cfd6e0"));
            Rectangle recent = new Rectangle(nx, ph * 0.56, 0, ph * 0.2);
            recent.setFill(HP_RED);
            Rectangle fill = new Rectangle(nx, ph * 0.56, barW * f.hpFraction(), ph * 0.2);
            fill.setFill(HP_GREEN);
            plateFill[side] = fill;
            plateRecent[side] = recent;
            Label hp = new Label();
            hp.setFont(Font.font("Consolas", FontWeight.BOLD, Math.max(10, ph * 0.17)));
            hp.setTextFill(Color.web("#4a5068"));
            hp.setLayoutX(nx + barW - 70);
            hp.setLayoutY(ph * 0.14);
            hp.setMinWidth(70);
            hp.setAlignment(Pos.CENTER_RIGHT);
            plateHp[side] = hp;

            plate.getChildren().addAll(shadow, body, tail, badge, code, name, back, recent, fill, hp);
            plate.setMouseTransparent(true);
            plates[side] = plate;
            int behind = root.getChildren().indexOf(views[0]);
            root.getChildren().add(behind >= 0 ? behind : root.getChildren().size(), plate); // detrás de los Digimon
            plateShownHp[side] = f.hp();
            hp.setText(f.hp() + "/" + f.maxHp);
            return;
        }

        // Vida nueva: la barra verde cae YA; el tramo rojo de daño reciente se encoge después.
        double old = plateShownHp[side], now = f.hp();
        if (Math.abs(old - now) < 0.01) return;
        plateShownHp[side] = now;
        double barW = plateBarW[side];
        Rectangle fill = plateFill[side], recent = plateRecent[side];
        double oldW = barW * Math.max(0, old) / f.maxHp, newW = barW * Math.max(0, now) / f.maxHp;
        fill.setWidth(newW);
        plateHp[side].setText(Math.round(now) + "/" + f.maxHp);
        if (newW < oldW) {
            recent.setX(fill.getX() + newW);
            recent.setWidth(oldW - newW);
            Timeline shrink = new Timeline(
                    new KeyFrame(Duration.seconds(0.35), new KeyValue(recent.widthProperty(), oldW - newW)),
                    new KeyFrame(Duration.seconds(0.85), new KeyValue(recent.widthProperty(), 0, Interpolator.EASE_IN)));
            shrink.play();
        } else {
            recent.setWidth(0);
        }
    }

    /** Muestra en la placa una vida dada sin animar (la siguiente actualización animará desde aquí). */
    private void setPlateHp(int side, ArenaFighter f, int hp) {
        if (!phone || plateFill[side] == null) return;
        plateShownHp[side] = hp;
        plateFill[side].setWidth(plateBarW[side] * Math.max(0, Math.min(1, hp / (double) f.maxHp)));
        plateRecent[side].setWidth(0);
        plateHp[side].setText(hp + "/" + f.maxHp);
    }

    private Image darkName(Image src) {
        if (src == null) return null;
        return darkNames.computeIfAbsent(src, s -> {
            int w = (int) s.getWidth(), h = (int) s.getHeight();
            WritableImage out = new WritableImage(w, h);
            PixelReader r = s.getPixelReader();
            PixelWriter pw = out.getPixelWriter();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = r.getArgb(x, y) >>> 24;
                    pw.setArgb(x, y, a == 0 ? 0 : (a << 24) | 0x1a2340); // letras claras -> tinta oscura
                }
            }
            return out;
        });
    }

    private static String attributeCode(int a) {
        return switch (a) {
            case 1 -> "Vi";
            case 2 -> "Da";
            case 3 -> "Va";
            case 4 -> "Fr";
            default -> "--";
        };
    }

    private static Color attributeColor(int a) {
        return switch (a) {
            case 1 -> Color.web("#8e44c9");
            case 2 -> Color.web("#f08a24");
            case 3 -> Color.web("#27ae60");
            case 4 -> Color.web("#1fb5b0");
            default -> Color.web("#7f8c8d");
        };
    }

    /** Diseño coliseo: las dos pantallas del coliseo, izquierda = tu equipo, derecha = rival. */
    private void drawHud(int side) {
        double w = SCREEN_W * width, h = SCREEN_H * height;
        double cx = (side == ArenaEngine.PLAYER ? LEFT_SCREEN_X : RIGHT_SCREEN_X) * width;
        Pane hud = huds[side];
        hud.getChildren().clear();
        hud.setLayoutX(cx - w / 2);
        hud.setLayoutY(SCREEN_Y * height - h / 2);
        hud.setPrefSize(w, h);

        VBox rows = new VBox(Math.max(2, h * 0.05));
        rows.setPadding(new Insets(h * 0.06, w * 0.05, 0, w * 0.05));
        for (int i = 0; i < 2; i++) {
            ArenaFighter f = engine.fighter(side, i);
            boolean isActive = engine.activeIndex(side) == i;
            Label marker = new Label(isActive ? "▶" : " ");
            marker.setTextFill(Color.web("#8fd6ff"));
            marker.setFont(Font.font("Consolas", FontWeight.BOLD, Math.max(9, h * 0.1)));
            ImageView nameView = new ImageView(f.nameImage);
            nameView.setSmooth(false);
            nameView.setPreserveRatio(true);
            nameView.setFitHeight(Math.max(8, h * 0.11));
            nameView.setFitWidth(w * 0.7);
            nameView.setOpacity(f.fainted() ? 0.35 : 1);
            HBox top = new HBox(4, marker, nameView);
            top.setAlignment(Pos.CENTER_LEFT);
            rows.getChildren().addAll(top, bar(w * 0.9, Math.max(5, h * 0.08), f.hpFraction(), hpColor(f.hpFraction())));
        }
        Label wLabel = new Label(engine.wAttackReady(side) ? "W-ATTACK ¡LISTO!" : "W-ATTACK");
        wLabel.setTextFill(Color.web(engine.wAttackReady(side) ? "#ffd84a" : "#aab3bc"));
        wLabel.setFont(Font.font("Consolas", FontWeight.BOLD, Math.max(8, h * 0.08)));
        rows.getChildren().addAll(wLabel,
                bar(w * 0.9, Math.max(4, h * 0.06), engine.gauge(side) / ArenaEngine.GAUGE_FULL, Color.web("#f0b428")));
        hud.getChildren().add(rows);
    }

    private static Pane bar(double w, double h, double fraction, Color color) {
        Rectangle back = new Rectangle(w, h, Color.web("#1a1a22"));
        back.setStroke(Color.web("#5a6470"));
        Rectangle fill = new Rectangle(Math.max(0, w * Math.min(1, fraction)), h, color);
        return new Pane(back, fill);
    }

    private static Color hpColor(double f) {
        return f > 0.5 ? Color.web("#3ecf5a") : f > 0.25 ? Color.web("#f0c93a") : Color.web("#e8453c");
    }

    // ---------------------------------------------------------------- turnos (contra la máquina)

    private void startPlayerTurn() {
        if (checkEnd()) return;
        refreshAll();
        double bp = engine.bpFactor(ArenaEngine.PLAYER);
        say("Tu turno: " + engine.active(ArenaEngine.PLAYER).name + " — BP x" + String.format("%.2f", bp));
        showActions(true);
    }

    private void playerAttack(boolean wAttack) {
        showActions(false);
        if (wAttack) {
            boolean protect = engine.cpuProtects();
            ArenaEngine.Hit hit = engine.attack(ArenaEngine.PLAYER, 0, engine.cpuDefense(), true, protect);
            animateHit(hit, this::cpuTurn);
            return;
        }
        say("¡Toca los números en orden y encadena el COMBO!");
        attackMinigame(engine.config().comboSeconds, combo -> {
            boolean protect = engine.cpuProtects(); // la máquina también puede usar PROTECT
            ArenaEngine.Hit hit = engine.attack(ArenaEngine.PLAYER, combo, engine.cpuDefense(), false, protect);
            animateHit(hit, this::cpuTurn);
        });
    }

    private void playerSwitch() {
        showActions(false);
        engine.switchActive(ArenaEngine.PLAYER);
        swapIn(ArenaEngine.PLAYER, () -> {
            say("¡Adelante, " + engine.active(ArenaEngine.PLAYER).name + "!");
            pause(0.8, this::cpuTurn);
        });
    }

    private void cpuTurn() {
        if (checkEnd()) return;
        refreshAll();
        switch (engine.cpuChoose()) {
            case SWITCH -> {
                engine.switchActive(ArenaEngine.CPU);
                swapIn(ArenaEngine.CPU, () -> {
                    say("El rival cambia de Digimon.");
                    pause(0.9, this::startPlayerTurn);
                });
            }
            case W_ATTACK -> {
                say("¡El rival prepara un W-ATTACK! ¿DEFENSE o PROTECT?");
                pause(0.6, () -> defendAgainstCpu(0, true));
            }
            default -> {
                int combo = engine.cpuCombo();
                say("¡El rival ataca! COMBO " + combo + " (AP +" + Math.round(engine.comboBonus(combo) * 100)
                        + "%). ¿DEFENSE o PROTECT?");
                pause(0.6, () -> defendAgainstCpu(combo, false));
            }
        }
    }

    /** Tu defensa contra la máquina: DEFENSE (minijuego) o PROTECT (tu compañero recibe el golpe). */
    private void defendAgainstCpu(int combo, boolean wAttack) {
        defenseChoice(engine.canSwitch(ArenaEngine.PLAYER), protect -> {
            if (protect) {
                animateHit(engine.attack(ArenaEngine.CPU, combo, 1.0, wAttack, true), this::startPlayerTurn);
            } else {
                defenseMinigame(d -> animateHit(engine.attack(ArenaEngine.CPU, combo, d, wAttack, false), this::startPlayerTurn));
            }
        });
    }

    private boolean checkEnd() {
        int winner = engine.winner();
        if (winner < 0) return false;
        finished = true;
        result = winner == ArenaEngine.PLAYER;
        refreshAll();
        showActions(false);
        // Solo el texto: la pose y el sonido de victoria o derrota llegan al volver (pedido del usuario).
        showBig(result ? "WIN!!" : "LOSE...");
        say(result ? "¡Tu equipo ganó la ARENA!" : "Tu equipo perdió esta vez.");
        addExitButton();
        return true;
    }

    private void addExitButton() {
        Button exit = new Button("SALIR");
        exit.setStyle(BUTTON);
        exit.setOnAction(e -> stage.close());
        exit.layoutXProperty().bind(root.widthProperty().subtract(exit.widthProperty()).divide(2));
        exit.setLayoutY(height * (phone ? 0.78 : 0.86));
        root.getChildren().add(exit);
    }

    // ---------------------------------------------------------------- minijuego de ataque

    /** Colores de los círculos (propios, parecidos a los rosas y amarillos de la app). */
    private static final Color[] CIRCLE_COLORS = {
            Color.web("#ff5fa8"), Color.web("#ffd23a"), Color.web("#ff5fa8"), Color.web("#5fd0ff"),
            Color.web("#ffd23a"), Color.web("#9b7bff"), Color.web("#3fd98a"), Color.web("#ff8a3a"),
    };

    /** Un número del minijuego (centro, velocidad y su nodo). */
    private static final class Ball {
        final int value;
        double x, y, vx, vy;
        boolean alive = true;
        StackPane node;
        Circle circle;

        Ball(int value) {
            this.value = value;
        }
    }

    /**
     * COMBO POR TIEMPO (como la app): siempre hay {@code ataque.numeros} números
     * a la vista; se tocan en orden y cada acierto lo reemplaza el siguiente
     * (con 5: 1-5, aciertas el 1 y aparece el 6...). Se mueven y rebotan. Un
     * número fuera de orden cuesta tiempo. Arriba, 10 círculos se llenan con el
     * combo: llenos = BIG ATTACK. Entrega el combo al terminar el tiempo.
     */
    private void attackMinigame(double seconds, IntConsumer onDone) {
        ArenaConfig c = engine.config();
        double areaW, areaH, areaX, areaY;
        if (phone) {
            areaX = width * 0.04;
            areaY = height * 0.655;
            areaW = width * 0.92;
            areaH = height * 0.33;
        } else {
            areaW = width * 0.5;
            areaH = height * 0.42;
            areaX = (width - areaW) / 2;
            areaY = height * 0.16;
        }
        double r = Math.max(16, Math.min(height * 0.04, areaH * 0.13));
        double top = 34; // franja del contador de combo
        double speed = c.attackCircleSpeed * areaH;

        Pane area = new Pane();
        area.setLayoutX(areaX);
        area.setLayoutY(areaY);
        area.setPrefSize(areaW, areaH);
        area.setMinSize(areaW, areaH);
        area.setMaxSize(areaW, areaH);
        area.setStyle("-fx-background-color: rgba(6,10,26,0.94); -fx-background-radius: 10;"
                + " -fx-border-color: #2b3a66; -fx-border-radius: 10; -fx-border-width: 2;");
        Rectangle clip = new Rectangle(areaW, areaH);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        area.setClip(clip);
        for (double gx = areaW / 8; gx < areaW; gx += areaW / 8) { // cuadrícula tenue (estilo app)
            Line l = new Line(gx, top, gx, areaH);
            l.setStroke(Color.web("#1b2a4d"));
            area.getChildren().add(l);
        }

        // Contador de 10 círculos + texto COMBO
        Circle[] dots = new Circle[COUNTER_DOTS];
        double dotR = Math.min(8, (areaW * 0.6) / COUNTER_DOTS / 2.6);
        for (int i = 0; i < COUNTER_DOTS; i++) {
            Circle d = new Circle(14 + dotR + i * dotR * 2.5, 17, dotR, Color.web("#1d2238"));
            d.setStroke(Color.web("#4a5373"));
            dots[i] = d;
            area.getChildren().add(d);
        }
        Label comboLabel = new Label("COMBO 0");
        comboLabel.setFont(Font.font("Arial Black", FontWeight.BOLD, 16));
        comboLabel.setTextFill(Color.WHITE);
        comboLabel.setLayoutX(areaW - 140);
        comboLabel.setLayoutY(6);
        Rectangle timer = new Rectangle(0, areaH - 5, areaW, 5);
        timer.setFill(CYAN);
        area.getChildren().addAll(comboLabel, timer);

        int[] combo = {0};
        int[] next = {1};
        int[] nextToSpawn = {1};
        boolean[] running = {false};
        boolean[] done = {false};
        double[] endAt = {0};
        List<Ball> balls = new ArrayList<>();
        AnimationTimer[] loop = new AnimationTimer[1];

        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            running[0] = false;
            if (loop[0] != null) loop[0].stop();
            stopClock();
            int value = combo[0];
            String rating = value >= c.comboForBig ? "EXCELLENT!!" : value >= 5 ? "GREAT!" : value >= 1 ? "GOOD" : "BAD...";
            showBig(rating);
            Sounds.play(value >= 1 ? Sfx.CONFIRMAR : Sfx.CANCELAR);
            showSub("COMBO " + value + "   AP +" + Math.round(engine.comboBonus(value) * 100) + "%"
                    + (engine.isBig(value) ? "   ★ BIG ATTACK" : ""));
            pause(1.1, () -> {
                minigameLayer.getChildren().remove(area);
                onDone.accept(value);
            });
        };

        // Crea un número en un lugar libre, con su color y su estrella
        java.util.function.Supplier<Ball> spawn = () -> {
            Ball b = new Ball(nextToSpawn[0]++);
            List<double[]> used = new ArrayList<>();
            for (Ball o : balls) if (o.alive) used.add(new double[]{o.x, o.y});
            double[] pos = freeSpot(used, areaW, areaH - top, r);
            b.x = pos[0];
            b.y = pos[1] + top;
            double angle = random.nextDouble() * Math.PI * 2;
            double v = speed * (0.75 + random.nextDouble() * 0.5);
            b.vx = Math.cos(angle) * v;
            b.vy = Math.sin(angle) * v;
            Color color = CIRCLE_COLORS[(b.value - 1) % CIRCLE_COLORS.length];
            Circle circle = new Circle(r, color);
            circle.setStroke(Color.WHITE);
            circle.setStrokeWidth(3);
            Polygon st = star(0, 0, r * 0.8, r * 0.38);
            st.setFill(Color.web("#ffffff", 0.35));
            Label num = new Label(String.valueOf(b.value));
            num.setTextFill(Color.WHITE);
            num.setFont(Font.font("Arial Black", FontWeight.BOLD, r * 0.85));
            num.setStyle("-fx-effect: dropshadow(gaussian, rgba(40,0,40,0.8), 3, 0.6, 0, 1);");
            StackPane node = new StackPane(circle, st, num);
            node.setLayoutX(b.x - r);
            node.setLayoutY(b.y - r);
            node.setStyle("-fx-cursor: hand;");
            node.setScaleX(0.2);
            node.setScaleY(0.2);
            ScaleTransition grow = new ScaleTransition(Duration.seconds(0.16), node);
            grow.setToX(1);
            grow.setToY(1);
            grow.play();
            b.node = node;
            b.circle = circle;
            // Al PRESIONAR (no al soltar): con el número en movimiento, el clic no se "escapa".
            node.setOnMousePressed(e -> {
                if (!running[0] || !b.alive) return;
                if (b.value == next[0]) {
                    b.alive = false;
                    combo[0]++;
                    next[0]++;
                    Sounds.play(Sfx.SELECCIONAR);
                    comboLabel.setText("COMBO " + combo[0]);
                    for (int i = 0; i < COUNTER_DOTS; i++) {
                        dots[i].setFill(i < combo[0] ? (combo[0] >= c.comboForBig ? ORANGE : YELLOW) : Color.web("#1d2238"));
                    }
                    ScaleTransition pop = new ScaleTransition(Duration.seconds(0.14), node);
                    pop.setToX(1.5);
                    pop.setToY(1.5);
                    FadeTransition fade = new FadeTransition(Duration.seconds(0.14), node);
                    fade.setToValue(0);
                    fade.setOnFinished(ev -> area.getChildren().remove(node));
                    pop.play();
                    fade.play();
                } else if (b.value > next[0]) {
                    // Fuera de orden: parpadea en rojo y cuesta tiempo
                    endAt[0] -= c.comboWrongPenalty * 1e9;
                    Sounds.play(Sfx.CANCELAR);
                    Color before = (Color) b.circle.getFill();
                    b.circle.setFill(Color.web("#e8453c"));
                    pause(0.2, () -> b.circle.setFill(before));
                    shake(node, 4, 4);
                }
            });
            balls.add(b);
            area.getChildren().add(node);
            return b;
        };

        loop[0] = new AnimationTimer() {
            private long last = -1;

            @Override
            public void handle(long now) {
                double dt = last < 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
                last = now;
                if (!running[0]) return;
                // Reponer: siempre ataque.numeros a la vista
                long alive = balls.stream().filter(b -> b.alive).count();
                while (alive < c.attackNumbers) {
                    spawn.get();
                    alive++;
                }
                for (Ball b : balls) {
                    if (!b.alive) continue;
                    b.x += b.vx * dt;
                    b.y += b.vy * dt;
                    if (b.x < r) { b.x = r; b.vx = Math.abs(b.vx); }
                    if (b.x > areaW - r) { b.x = areaW - r; b.vx = -Math.abs(b.vx); }
                    if (b.y < r + top) { b.y = r + top; b.vy = Math.abs(b.vy); }
                    if (b.y > areaH - r - 6) { b.y = areaH - r - 6; b.vy = -Math.abs(b.vy); }
                }
                for (int i = 0; i < balls.size(); i++) {
                    Ball a = balls.get(i);
                    if (!a.alive) continue;
                    for (int j = i + 1; j < balls.size(); j++) {
                        Ball b = balls.get(j);
                        if (!b.alive) continue;
                        double dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy);
                        if (d >= 2 * r || d == 0) continue;
                        // Choque elástico de masas iguales: intercambian la velocidad en el eje que los une.
                        double nx = dx / d, ny = dy / d;
                        double rel = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny;
                        if (rel > 0) {
                            a.vx -= rel * nx;
                            a.vy -= rel * ny;
                            b.vx += rel * nx;
                            b.vy += rel * ny;
                        }
                        double push = (2 * r - d) / 2;
                        a.x -= nx * push;
                        a.y -= ny * push;
                        b.x += nx * push;
                        b.y += ny * push;
                    }
                }
                for (Ball b : balls) {
                    if (!b.alive) continue;
                    b.node.setLayoutX(b.x - r);
                    b.node.setLayoutY(b.y - r);
                }
                balls.removeIf(b -> !b.alive && b.node.getParent() == null);
                double remaining = Math.max(0, (endAt[0] - now) / 1e9);
                timer.setWidth(areaW * remaining / seconds);
                setClock(remaining / seconds);
                if (remaining <= 0) finish.run();
            }
        };

        minigameLayer.getChildren().add(area);
        showBig("READY...");
        Sounds.play(Sfx.LISTO);
        pause(0.7, () -> {
            showBig("START!");
            Sounds.play(Sfx.GO);
            for (int i = 0; i < c.attackNumbers; i++) spawn.get();
            endAt[0] = System.nanoTime() + seconds * 1e9;
            running[0] = true;
            loop[0].start();
        });
    }

    /** Un lugar dentro del área que no pise otro círculo (tras varios intentos, el que salga). */
    private double[] freeSpot(List<double[]> used, double w, double h, double r) {
        double[] best = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            double x = r + 4 + random.nextDouble() * (w - 2 * r - 8);
            double y = r + 4 + random.nextDouble() * Math.max(1, h - 2 * r - 14);
            best = new double[]{x, y};
            boolean ok = true;
            for (double[] u : used) {
                if (Math.hypot(u[0] - x, u[1] - y) < r * 2.4) { ok = false; break; }
            }
            if (ok) return best;
        }
        return best;
    }

    // ---------------------------------------------------------------- reloj

    private void setClock(double fraction) {
        if (clockArc == null) return;
        clockArc.setLength(360 * Math.max(0, Math.min(1, fraction)));
        clockArc.setStroke(fraction < 0.25 ? Color.web("#ff5a3a") : YELLOW);
    }

    private void stopClock() {
        setClock(1);
    }

    // ---------------------------------------------------------------- minijuego de defensa

    /**
     * Barra con un indicador que va y viene; se detiene con STOP, clic o
     * ESPACIO. Escudo en el centro (zona perfecta), tramos amarillos (buena) y
     * verdes en los extremos, como en la app. Entrega la distancia al centro (0..1).
     */
    private void defenseMinigame(DoubleConsumer onDone) {
        ArenaConfig c = engine.config();
        double panelX, panelY, panelW, panelH;
        if (phone) {
            panelX = width * 0.04;
            panelY = height * 0.655;
            panelW = width * 0.92;
            panelH = height * 0.33;
        } else {
            panelW = width * 0.5;
            panelH = height * 0.3;
            panelX = (width - panelW) / 2;
            panelY = height * 0.35;
        }
        Pane panel = new Pane();
        panel.setLayoutX(panelX);
        panel.setLayoutY(panelY);
        panel.setPrefSize(panelW, panelH);
        panel.setStyle("-fx-background-color: rgba(6,10,26,0.94); -fx-background-radius: 10;"
                + " -fx-border-color: #2b3a66; -fx-border-radius: 10; -fx-border-width: 2; -fx-cursor: hand;");

        double trackW = panelW * 0.86, trackH = Math.max(16, panelH * 0.14);
        double x0 = (panelW - trackW) / 2, y0 = panelH * 0.28;
        // Tramos en forma de chevrón: verde en los extremos, amarillo en la zona buena, escudo en la perfecta
        int segments = 14;
        for (int i = 0; i < segments; i++) {
            double segW = trackW / segments;
            double mid = (i + 0.5) / segments; // 0..1
            double dist = Math.abs(mid - 0.5) * 2;
            Color col = dist <= c.perfectZone ? Color.web("#2f8fe8") : dist <= c.goodZone ? Color.web("#ffe03a")
                    : dist > 0.75 ? Color.web("#b6ff3a") : Color.web("#2a2f3a");
            double sx = x0 + i * segW;
            boolean left = mid < 0.5;
            Polygon chev = left
                    ? new Polygon(sx + 3, y0, sx + segW, y0, sx + segW - 4, y0 + trackH, sx - 1, y0 + trackH)
                    : new Polygon(sx, y0, sx + segW - 3, y0, sx + segW + 1, y0 + trackH, sx + 4, y0 + trackH);
            chev.setFill(col);
            chev.setStroke(Color.web("#0b1020"));
            panel.getChildren().add(chev);
        }
        // Escudo en el centro
        double sc = trackH * 1.25, scx = x0 + trackW / 2, scy = y0 + trackH / 2;
        Polygon shield = new Polygon(scx - sc * 0.7, scy - sc * 0.8, scx + sc * 0.7, scy - sc * 0.8,
                scx + sc * 0.7, scy + sc * 0.05, scx, scy + sc * 0.9, scx - sc * 0.7, scy + sc * 0.05);
        shield.setFill(Color.web("#2f8fe8"));
        shield.setStroke(Color.WHITE);
        shield.setStrokeWidth(3);
        Rectangle marker = new Rectangle(7, trackH * 2.2, Color.WHITE);
        marker.setStroke(INK);
        marker.setEffect(new DropShadow(10, CYAN));
        marker.setLayoutY(y0 - trackH * 0.6);
        marker.setLayoutX(x0 - 3);
        // Botón STOP
        double bw = panelW * 0.5, bh = panelH * 0.24;
        Pane stop = new Pane();
        Polygon stopBody = new Polygon(bh * 0.3, 0, bw, 0, bw - bh * 0.3, bh, 0, bh);
        stopBody.setFill(Color.web("#3fc8f0"));
        stopBody.setStroke(Color.WHITE);
        stopBody.setStrokeWidth(2);
        Label stopText = new Label("STOP");
        stopText.setFont(Font.font("Arial Black", FontWeight.BOLD, bh * 0.45));
        stopText.setTextFill(INK);
        stopText.setLayoutX(bw * 0.35);
        stopText.setLayoutY(bh * 0.16);
        stop.getChildren().addAll(stopBody, stopText);
        stop.setLayoutX((panelW - bw) / 2);
        stop.setLayoutY(panelH * 0.66);
        Label hint = new Label("¡STOP (o ESPACIO) con el indicador en el escudo!");
        hint.setTextFill(Color.WHITE);
        hint.setFont(Font.font("Consolas", FontWeight.BOLD, 13));
        hint.setLayoutX(x0);
        hint.setLayoutY(8);
        panel.getChildren().addAll(shield, marker, stop, hint);

        double[] pos = {0};
        boolean[] done = {false};
        long[] start = {-1};
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (start[0] < 0) start[0] = now;
                double t = (now - start[0]) / 1e9;
                double phase = (t * c.defenseSweepsPerSecond) % 1.0;
                pos[0] = phase < 0.5 ? phase * 2 : 2 - phase * 2; // 0 → 1 → 0
                marker.setLayoutX(x0 + pos[0] * trackW - 3);
                setClock(1 - t / c.defenseSeconds);
                if (t >= c.defenseSeconds) stopDefense.run();
            }
        };
        stopDefense = () -> {
            if (done[0]) return;
            done[0] = true;
            timer.stop();
            stopDefense = null;
            stopClock();
            double seconds = start[0] < 0 ? 0 : (System.nanoTime() - start[0]) / 1e9;
            double distance = seconds >= c.defenseSeconds ? 1.0 : Math.abs(pos[0] - 0.5) * 2;
            double factor = engine.defenseFactor(distance);
            showBig(factor <= c.perfectFactor ? "EXCELLENT!!" : factor < 1 ? "GOOD!" : "BAD...");
            Sounds.play(factor < 1 ? Sfx.CONFIRMAR : Sfx.CANCELAR);
            showSub(factor < 1 ? "BP UP!  Daño -" + Math.round((1 - factor) * 100) + "%" : "Sin defensa");
            if (factor <= c.perfectFactor) pulse(shield);
            pause(0.8, () -> {
                minigameLayer.getChildren().remove(panel);
                onDone.accept(distance);
            });
        };
        panel.setOnMousePressed(e -> { if (stopDefense != null) stopDefense.run(); });
        minigameLayer.getChildren().add(panel);
        timer.start();
    }

    private static void pulse(Node n) {
        ScaleTransition s = new ScaleTransition(Duration.seconds(0.12), n);
        s.setToX(1.4);
        s.setToY(1.4);
        s.setAutoReverse(true);
        s.setCycleCount(4);
        s.play();
    }

    // ---------------------------------------------------------------- animaciones

    /**
     * Anima exactamente lo que dice el Hit del motor y luego sigue con {@code then}:
     * (W-ATTACK: escena especial con los dos) → pose de ATAQUE con impulso → el
     * Digimon EXPULSA su ataque (BIG si combo 10+ o W-ATTACK; si no SMALL) → impacto,
     * destello, temblor, número de daño y vida que cae → GUTS!!! si aguantó → caída.
     */
    private void animateHit(ArenaEngine.Hit hit, Runnable then) {
        if (!hit.connected()) { // turno perdido (online, sin elegir a tiempo)
            say((hit.attackerSide() == ArenaEngine.PLAYER ? "Perdiste" : "El rival perdió") + " el turno.");
            pause(0.9, then);
            return;
        }
        if (hit.wAttack()) {
            wAttackCutIn(hit.attackerSide(), () -> strike(hit, then));
        } else {
            strike(hit, then);
        }
    }

    private void strike(ArenaEngine.Hit hit, Runnable then) {
        int d = 1 - hit.attackerSide();
        if (hit.protect()) {
            // PROTECT: el compañero del defensor aparece adelante y recibe el golpe
            ArenaFighter protector = engine.fighter(d, 1 - spriteIndex[d]);
            protectorEnter(d, protector, protector.hp() + hit.damage(), pv -> strikeAt(hit, then, pv, protector,
                    new double[]{pv.getLayoutX() + pv.getFitWidth() / 2, pv.getLayoutY() + pv.getFitHeight() / 2}));
        } else {
            strikeAt(hit, then, views[d], engine.fighter(d, spriteIndex[d]), fighterCenter(d));
        }
    }

    /** Pose de ataque, impulso y proyectil hacia {@code targetView} (el activo, o el compañero que protege). */
    private void strikeAt(ArenaEngine.Hit hit, Runnable then, ImageView targetView, ArenaFighter targetFighter, double[] to) {
        int a = hit.attackerSide(), d = 1 - a;
        ArenaFighter attacker = engine.active(a);
        ImageView av = views[a];
        boolean big = hit.big();
        busy[a] = true;
        av.setImage(attacker.attack);
        if (big && !hit.wAttack()) {
            showBig("BIG ATTACK!!");
            Sounds.play(Sfx.CUTIN_GRANDE);
        }

        // Impulso: un pasito atrás y luego hacia el rival
        double[] from = fighterCenter(a);
        double dx = to[0] - from[0], dy = to[1] - from[1], len = Math.max(1, Math.hypot(dx, dy));
        TranslateTransition back = new TranslateTransition(Duration.seconds(0.08), av);
        back.setToX(-dx / len * spriteW() * 0.06);
        back.setToY(-dy / len * spriteW() * 0.06);
        TranslateTransition lunge = new TranslateTransition(Duration.seconds(0.12), av);
        lunge.setToX(dx / len * spriteW() * 0.12);
        lunge.setToY(dy / len * spriteW() * 0.12);
        TranslateTransition home = new TranslateTransition(Duration.seconds(0.2), av);
        home.setToX(0);
        home.setToY(0);
        SequentialTransition windup = new SequentialTransition(back, lunge);

        windup.setOnFinished(e -> {
            home.play();
            ArenaFighter mate = hit.wAttack() ? engine.fighter(a, 1 - engine.activeIndex(a)) : null;
            ImageView ghost = mate != null ? partnerGhost(a, mate) : null;
            int effectId = big ? attacker.bigAttack : attacker.smallAttack;
            Image effect = big ? attackSprites.getBigAttack(effectId) : attackSprites.getSmallAttack(effectId);
            boolean[] resolved = {false};
            Runnable arrive = () -> {
                if (resolved[0]) return;
                resolved[0] = true;
                resolveHit(hit, a, d, av, targetView, targetFighter, big, then);
            };
            Sounds.play(big ? Sfx.ATAQUE_GRANDE : Sfx.ATAQUE);
            fireProjectile(from, to, effect, big, arrive);
            if (ghost != null) {
                Image mateEffect = attackSprites.getBigAttack(mate.bigAttack);
                double[] ghostFrom = {ghost.getLayoutX() + ghost.getFitWidth() / 2, ghost.getLayoutY() + ghost.getFitHeight() / 2};
                pause(0.12, () -> fireProjectile(ghostFrom, to, mateEffect, true, () -> { }));
                pause(1.2, () -> {
                    FadeTransition out = new FadeTransition(Duration.seconds(0.3), ghost);
                    out.setToValue(0);
                    out.setOnFinished(ev -> root.getChildren().remove(ghost));
                    out.play();
                });
            }
            if (effect == null) pause(PROJECTILE_SECONDS * 0.6, arrive); // sin sprite de ataque: igual se resuelve
        });
        windup.play();
    }

    /** Lo que pasa cuando el ataque llega: impacto, destello, temblor, daño y vida que cae. */
    private void resolveHit(ArenaEngine.Hit hit, int a, int d, ImageView av, ImageView dv, ArenaFighter target,
                            boolean big, Runnable then) {
        boolean protect = hit.protect();
        if (hit.defenderFainted() && !protect) holdPanel[d] = true; // su barra cae a 0 antes de que entre el compañero
        Sounds.play(Sfx.GOLPE);
        showImpact(dv, big);
        screenFlash(big ? 0.55 : 0.25);
        shake(dv, big ? 10 : 6, big ? 8 : 6);
        if (big) shake(root, 6, 6); // golpe fuerte: tiembla toda la pantalla
        if (!protect) busy[d] = true;
        dv.setImage(target.dodge); // retroceso
        TranslateTransition recoil = new TranslateTransition(Duration.seconds(0.12), dv);
        recoil.setByX((d == ArenaEngine.PLAYER ? -1 : 1) * spriteW() * 0.1);
        recoil.setAutoReverse(true);
        recoil.setCycleCount(2);
        recoil.play();
        damageNumber(hit.damage(), dv, big);
        refreshAll(); // la vida cae al instante (con el tramo rojo de daño reciente)
        pause(0.3, () -> {
            busy[a] = false;
            av.setImage(engine.active(a).idle1);
        });
        pause(0.55, () -> {
            if (!hit.defenderFainted()) {
                if (!protect) busy[d] = false;
                dv.setImage(target.idle1);
            }
        });
        Runnable after = () -> pause(0.5, () -> {
            if (protect) {
                protectorExit(d, dv, target, hit.defenderFainted(), then); // el activo sigue siendo el mismo
            } else if (hit.defenderFainted()) {
                faint(d, hit.defenderReplaced(), then);
            } else {
                then.run();
            }
        });
        if (hit.guts()) pause(0.6, () -> gutsCutIn(d, target, after));
        else pause(0.45, after);
    }

    /** Dónde se para el compañero que protege: delante del activo, hacia el atacante. */
    private double[] protectorCenter(int side) {
        double[] me = fighterCenter(side), rival = fighterCenter(1 - side);
        double dx = rival[0] - me[0], dy = rival[1] - me[1], len = Math.max(1, Math.hypot(dx, dy));
        return new double[]{me[0] + dx / len * spriteW() * 0.45, me[1] + dy / len * spriteW() * 0.45};
    }

    /**
     * PROTECT: el compañero sale de detrás del activo y se pone delante (el
     * activo queda atenuado); su placa reemplaza un momento a la del activo.
     */
    private void protectorEnter(int side, ArenaFighter protector, int hpBeforeHit, Consumer<ImageView> ready) {
        ImageView pv = new ImageView(protector.idle1);
        pv.setSmooth(false);
        org.example.animation.PixelScale.crisp(pv);
        pv.setScaleX(side == ArenaEngine.PLAYER ? -1 : 1);
        pv.setMouseTransparent(true);
        double[] c = protectorCenter(side), base = fighterCenter(side);
        placeSprite(pv, protector, c[0], c[1]);
        pv.setTranslateX(base[0] - c[0]);
        pv.setTranslateY(base[1] - c[1]);
        pv.setOpacity(0);
        root.getChildren().add(root.getChildren().indexOf(views[side]) + 1, pv);
        forcedPlate[side] = 1 - spriteIndex[side];
        refreshAll();
        setPlateHp(side, protector, hpBeforeHit); // el motor ya restó el golpe: la placa cae recién al impacto
        say("¡" + protector.name + " protege a su compañero!");
        TranslateTransition in = new TranslateTransition(Duration.seconds(0.25), pv);
        in.setToX(0);
        in.setToY(0);
        in.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition show = new FadeTransition(Duration.seconds(0.2), pv);
        show.setToValue(1);
        FadeTransition dim = new FadeTransition(Duration.seconds(0.2), views[side]);
        dim.setToValue(0.45);
        ParallelTransition enter = new ParallelTransition(in, show, dim);
        enter.setOnFinished(e -> pause(0.15, () -> ready.accept(pv)));
        enter.play();
    }

    /** El compañero que protegió vuelve atrás (o cae, si el golpe lo dejó KO); el activo sigue igual. */
    private void protectorExit(int side, ImageView pv, ArenaFighter protector, boolean fainted, Runnable then) {
        double[] c = protectorCenter(side), base = fighterCenter(side);
        javafx.animation.Transition move;
        if (fainted) {
            say("¡" + protector.name + " cayó protegiendo a su compañero!");
            pv.setImage(protector.dodge);
            TranslateTransition sink = new TranslateTransition(Duration.seconds(0.6), pv);
            sink.setByY(spriteH() * 0.25);
            move = sink;
        } else {
            TranslateTransition back = new TranslateTransition(Duration.seconds(0.3), pv);
            back.setToX(base[0] - c[0]);
            back.setToY(base[1] - c[1]);
            move = back;
        }
        FadeTransition out = new FadeTransition(Duration.seconds(fainted ? 0.6 : 0.3), pv);
        out.setToValue(0);
        FadeTransition undim = new FadeTransition(Duration.seconds(0.3), views[side]);
        undim.setToValue(1);
        ParallelTransition leave = new ParallelTransition(move, out, undim);
        leave.setOnFinished(e -> {
            root.getChildren().remove(pv);
            forcedPlate[side] = -1;
            refreshAll(); // vuelve la placa del activo
            pause(0.3, then);
        });
        leave.play();
    }

    /**
     * El proyectil del ataque: sale del atacante hacia el rival, girado en la
     * dirección del viaje (la pantalla celular es diagonal). Al llegar se apaga y
     * llama a {@code onArrive}.
     */
    private void fireProjectile(double[] from, double[] to, Image effect, boolean big, Runnable onArrive) {
        if (effect == null) return;
        ImageView fx = new ImageView(effect);
        fx.setSmooth(false);
        fx.setPreserveRatio(true);
        double size = (big ? 42 : 28) * spriteScale * (phone ? 0.9 : 1);
        fx.setFitWidth(size);
        fx.setMouseTransparent(true);
        double dx = to[0] - from[0], dy = to[1] - from[1];
        boolean goesRight = dx > 0;
        fx.setScaleX(goesRight != EFFECT_FACES_RIGHT ? -1 : 1);
        double angle = Math.toDegrees(Math.atan2(dy, Math.abs(dx)));
        fx.setRotate(goesRight ? angle : -angle);
        double h = effect.getHeight() * size / Math.max(1, effect.getWidth());
        fx.setLayoutX(from[0] - size / 2);
        fx.setLayoutY(from[1] - h / 2);
        root.getChildren().add(root.getChildren().indexOf(flash), fx);

        TranslateTransition go = new TranslateTransition(Duration.seconds(PROJECTILE_SECONDS), fx);
        go.setToX(dx);
        go.setToY(dy);
        go.setInterpolator(Interpolator.EASE_IN);
        go.setOnFinished(e -> {
            root.getChildren().remove(fx);
            onArrive.run();
        });
        go.play();
    }

    /** El compañero aparece un momento detrás del atacante y dispara también (W-ATTACK). */
    private ImageView partnerGhost(int side, ArenaFighter mate) {
        ImageView g = new ImageView(mate.attack != null ? mate.attack : mate.idle1);
        g.setSmooth(false);
        org.example.animation.PixelScale.crisp(g);
        g.setScaleX(side == ArenaEngine.PLAYER ? -1 : 1);
        double[] c = fighterCenter(side);
        double off = spriteW() * 0.45;
        placeSprite(g, mate, c[0] + (side == ArenaEngine.PLAYER ? -off : off), c[1] + (side == ArenaEngine.PLAYER ? off * 0.4 : -off * 0.4));
        g.setOpacity(0);
        g.setMouseTransparent(true);
        root.getChildren().add(root.getChildren().indexOf(views[side]), g);
        FadeTransition in = new FadeTransition(Duration.seconds(0.15), g);
        in.setToValue(0.9);
        in.play();
        return g;
    }

    /**
     * Escena especial del W-ATTACK (como la app): fondo negro, líneas amarillas
     * de velocidad, un rayo, y los DOS Digimon del equipo juntos en pose de ataque.
     */
    private void wAttackCutIn(int side, Runnable then) {
        boolean mine = side == ArenaEngine.PLAYER;
        Sounds.play(Sfx.CUTIN_GRANDE);
        Pane cut = new Pane();
        cut.setPrefSize(width, height);
        Rectangle black = new Rectangle(width, height, Color.BLACK);
        cut.getChildren().add(black);
        List<TranslateTransition> streaks = new ArrayList<>();
        Random rnd = new Random();
        for (int i = 0; i < 18; i++) {
            double y = height * (0.2 + rnd.nextDouble() * 0.6);
            Rectangle s = new Rectangle(width * (0.2 + rnd.nextDouble() * 0.5), 1 + rnd.nextInt(3));
            s.setFill(i % 3 == 0 ? Color.WHITE : YELLOW);
            s.setOpacity(0.5 + rnd.nextDouble() * 0.5);
            s.setLayoutY(y);
            s.setLayoutX(mine ? width : -s.getWidth());
            TranslateTransition t = new TranslateTransition(Duration.seconds(0.25 + rnd.nextDouble() * 0.25), s);
            t.setToX(mine ? -(width + s.getWidth()) : width + s.getWidth());
            t.setCycleCount(Animation.INDEFINITE);
            t.setDelay(Duration.seconds(rnd.nextDouble() * 0.3));
            streaks.add(t);
            cut.getChildren().add(s);
        }
        Polyline bolt = new Polyline();
        double by = height * 0.66;
        for (int i = 0; i <= 12; i++) bolt.getPoints().addAll(width * i / 12.0, by + (i % 2 == 0 ? -1 : 1) * height * 0.025);
        bolt.setStroke(YELLOW);
        bolt.setStrokeWidth(3);
        bolt.setEffect(new DropShadow(16, ORANGE));
        cut.getChildren().add(bolt);

        double big = spriteScale * 1.5;
        ArenaFighter first = engine.active(side), second = engine.fighter(side, 1 - engine.activeIndex(side));
        ImageView[] pair = {new ImageView(second.attack != null ? second.attack : second.idle1),
                new ImageView(first.attack != null ? first.attack : first.idle1)};
        for (int i = 0; i < 2; i++) {
            ImageView v = pair[i];
            v.setSmooth(false);
            v.setFitWidth(64 * big);
            v.setFitHeight(56 * big);
            v.setScaleX(mine ? -1 : 1);
            double cx = width * (mine ? 0.32 + i * 0.22 : 0.68 - i * 0.22);
            v.setLayoutX(cx - 32 * big);
            v.setLayoutY(height * 0.5 - 28 * big);
            v.setTranslateX(mine ? -width : width);
            cut.getChildren().add(v);
            TranslateTransition in = new TranslateTransition(Duration.seconds(0.28), v);
            in.setDelay(Duration.seconds(0.1 + i * 0.12));
            in.setToX(0);
            in.setInterpolator(Interpolator.EASE_OUT);
            in.play();
        }
        Label title = new Label("W-ATTACK!!");
        title.setFont(Font.font("Arial Black", FontWeight.BOLD, phone ? 46 : 40));
        title.setTextFill(YELLOW);
        title.setStyle("-fx-effect: dropshadow(gaussian, #ff5a1e, 10, 0.7, 0, 0);");
        title.setLayoutY(height * 0.16);
        title.layoutXProperty().bind(cut.widthProperty().subtract(title.widthProperty()).divide(2));
        cut.getChildren().add(title);

        cut.setOpacity(0);
        overlayLayer.getChildren().add(cut);
        streaks.forEach(TranslateTransition::play);
        FadeTransition in = new FadeTransition(Duration.seconds(0.15), cut);
        in.setToValue(1);
        in.play();
        Timeline flick = new Timeline(new KeyFrame(Duration.seconds(0.07), e -> bolt.setOpacity(bolt.getOpacity() > 0.5 ? 0.3 : 1)));
        flick.setCycleCount(Animation.INDEFINITE);
        flick.play();
        pause(1.45, () -> {
            screenFlash(0.8);
            FadeTransition out = new FadeTransition(Duration.seconds(0.2), cut);
            out.setToValue(0);
            out.setOnFinished(e -> {
                streaks.forEach(TranslateTransition::stop);
                flick.stop();
                overlayLayer.getChildren().remove(cut);
                then.run();
            });
            out.play();
        });
    }

    /** Pantalla GUTS!!! (como la app): roja/magenta, el Digimon aguanta con 1 HP. */
    private void gutsCutIn(int side, ArenaFighter who, Runnable then) {
        Pane cut = new Pane();
        cut.setPrefSize(width, height);
        Rectangle tint = new Rectangle(width, height);
        tint.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, Color.web("#ff1f5a", 0.85)), new javafx.scene.paint.Stop(1, Color.web("#b0109a", 0.85))));
        ImageView v = new ImageView(who.idle1);
        double s = spriteScale * 1.6;
        v.setSmooth(false);
        v.setFitWidth(64 * s);
        v.setFitHeight(56 * s);
        v.setScaleX(side == ArenaEngine.PLAYER ? -1 : 1);
        v.setLayoutX(width / 2 - 32 * s);
        v.setLayoutY(height * 0.5 - 28 * s);
        Label text = new Label("GUTS!!!");
        text.setFont(Font.font("Arial Black", FontWeight.BOLD, phone ? 58 : 50));
        text.setTextFill(Color.WHITE);
        text.setStyle("-fx-effect: dropshadow(gaussian, #4a0020, 10, 0.8, 0, 2);");
        text.setLayoutY(height * 0.18);
        text.layoutXProperty().bind(cut.widthProperty().subtract(text.widthProperty()).divide(2));
        cut.getChildren().addAll(tint, v, text);
        cut.setOpacity(0);
        overlayLayer.getChildren().add(cut);
        FadeTransition in = new FadeTransition(Duration.seconds(0.12), cut);
        in.setToValue(1);
        in.play();
        shake(v, 8, 10);
        ScaleTransition zoom = new ScaleTransition(Duration.seconds(0.25), text);
        zoom.setFromX(2.2);
        zoom.setFromY(2.2);
        zoom.setToX(1);
        zoom.setToY(1);
        zoom.play();
        say((side == ArenaEngine.PLAYER ? "¡Tu Digimon" : "¡El rival") + " aguantó con 1 HP!");
        pause(1.2, () -> {
            FadeTransition out = new FadeTransition(Duration.seconds(0.2), cut);
            out.setToValue(0);
            out.setOnFinished(e -> {
                overlayLayer.getChildren().remove(cut);
                then.run();
            });
            out.play();
        });
    }

    private void showImpact(ImageView target, boolean big) {
        double size = Math.max(32, spriteW() * (big ? 1.1 : 0.7));
        impactView.setFitWidth(size);
        impactView.setPreserveRatio(true);
        impactView.setLayoutX(target.getLayoutX() + target.getFitWidth() / 2 - size / 2);
        impactView.setLayoutY(target.getLayoutY() + target.getFitHeight() / 2 - size / 2);
        impactView.setVisible(true);
        impactView.setOpacity(1);
        impactView.setRotate(random.nextInt(4) * 90);
        impactView.toFront();
        ScaleTransition grow = new ScaleTransition(Duration.seconds(0.1), impactView);
        grow.setFromX(0.5);
        grow.setFromY(0.5);
        grow.setToX(1);
        grow.setToY(1);
        grow.play();
        FadeTransition out = new FadeTransition(Duration.seconds(0.3), impactView);
        out.setDelay(Duration.seconds(0.15));
        out.setToValue(0);
        out.setOnFinished(e -> impactView.setVisible(false));
        out.play();
    }

    /** Temblor horizontal (vuelve a su sitio). */
    private static void shake(Node node, double amount, int times) {
        TranslateTransition t = new TranslateTransition(Duration.seconds(0.035), node);
        t.setFromX(-amount);
        t.setToX(amount);
        t.setAutoReverse(true);
        t.setCycleCount(times);
        t.setOnFinished(e -> node.setTranslateX(0));
        t.play();
    }

    private void screenFlash(double strength) {
        flash.setOpacity(strength);
        FadeTransition f = new FadeTransition(Duration.seconds(0.3), flash);
        f.setToValue(0);
        f.play();
    }

    /** Cae: se hunde y se desvanece; si queda compañero, entra él. */
    private void faint(int side, boolean replaced, Runnable then) {
        ImageView v = views[side];
        busy[side] = true;
        v.setImage(engine.fighter(side, spriteIndex[side]).dodge);
        say((side == ArenaEngine.PLAYER ? "¡Tu " : "¡El rival ") + "Digimon cayó!");
        TranslateTransition sink = new TranslateTransition(Duration.seconds(0.6), v);
        sink.setByY(spriteH() * 0.25);
        FadeTransition out = new FadeTransition(Duration.seconds(0.6), v);
        out.setToValue(0);
        ParallelTransition fall = new ParallelTransition(sink, out);
        fall.setOnFinished(e -> {
            busy[side] = false;
            holdPanel[side] = false;
            if (replaced) {
                swapIn(side, () -> {
                    say("¡Entra " + engine.active(side).name + "!");
                    pause(0.8, then);
                });
            } else {
                then.run();
            }
        });
        fall.play();
    }

    /** Cambio de Digimon: el que estaba sale deslizándose hacia su borde y el nuevo entra desde ahí. */
    private void swapIn(int side, Runnable then) {
        ImageView v = views[side];
        double edge = (side == ArenaEngine.PLAYER ? -1 : 1) * width * 0.45;
        TranslateTransition out = new TranslateTransition(Duration.seconds(0.25), v);
        out.setToX(edge);
        FadeTransition outFade = new FadeTransition(Duration.seconds(0.25), v);
        outFade.setToValue(0);
        ParallelTransition leave = new ParallelTransition(out, outFade);
        leave.setOnFinished(e -> {
            placeFighter(side);
            v.setTranslateX(edge);
            v.setOpacity(0);
            refreshAll();
            TranslateTransition in = new TranslateTransition(Duration.seconds(0.35), v);
            in.setToX(0);
            in.setInterpolator(Interpolator.EASE_OUT);
            FadeTransition inFade = new FadeTransition(Duration.seconds(0.35), v);
            inFade.setToValue(1);
            ParallelTransition enter = new ParallelTransition(in, inFade);
            enter.setOnFinished(ev -> then.run());
            enter.play();
        });
        leave.play();
    }

    /** Número de daño grande (amarillo-naranja con borde oscuro, como la app) que salta y sube. */
    private void damageNumber(int damage, ImageView over, boolean big) {
        Label l = new Label(String.valueOf(damage));
        double size = (phone ? 34 : 26) * (big ? 1.25 : 1);
        l.setFont(Font.font("Arial Black", FontWeight.BOLD, size));
        l.setTextFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, Color.web("#fff36a")), new javafx.scene.paint.Stop(1, Color.web("#ff8a1e"))));
        l.setStyle("-fx-effect: dropshadow(gaussian, #3a0a00, 4, 1.0, 0, 0);");
        l.setLayoutX(over.getLayoutX() + spriteW() * 0.25);
        l.setLayoutY(over.getLayoutY() - size * 0.2);
        l.setMouseTransparent(true);
        root.getChildren().add(l);
        ScaleTransition pop = new ScaleTransition(Duration.seconds(0.14), l);
        pop.setFromX(2);
        pop.setFromY(2);
        pop.setToX(1);
        pop.setToY(1);
        pop.play();
        TranslateTransition up = new TranslateTransition(Duration.seconds(0.9), l);
        up.setDelay(Duration.seconds(0.25));
        up.setByY(-spriteH() * 0.35);
        FadeTransition fade = new FadeTransition(Duration.seconds(0.5), l);
        fade.setDelay(Duration.seconds(0.65));
        fade.setToValue(0);
        up.play();
        fade.setOnFinished(e -> root.getChildren().remove(l));
        fade.play();
    }

    private void showBig(String text) {
        bigText.setText(text);
        bigText.setVisible(true);
        bigText.setOpacity(1);
        ScaleTransition pop = new ScaleTransition(Duration.seconds(0.15), bigText);
        pop.setFromX(1.6);
        pop.setFromY(1.6);
        pop.setToX(1);
        pop.setToY(1);
        pop.play();
        if (finished && (endHold || text.startsWith("WIN") || text.startsWith("LOSE"))) return; // el final se queda
        FadeTransition f = new FadeTransition(Duration.seconds(0.6), bigText);
        f.setDelay(Duration.seconds(0.7));
        f.setToValue(0);
        f.play();
    }

    private void showSub(String text) {
        subText.setText(text);
        subText.setVisible(true);
        subText.setOpacity(1);
        FadeTransition f = new FadeTransition(Duration.seconds(0.5), subText);
        f.setDelay(Duration.seconds(0.9));
        f.setToValue(0);
        f.play();
    }

    private void say(String text) {
        message.setText(text);
    }

    private static void pause(double seconds, Runnable then) {
        PauseTransition p = new PauseTransition(Duration.seconds(seconds));
        p.setOnFinished(e -> then.run());
        p.play();
    }

    private static Image load(String resource) {
        try (InputStream in = ArenaScreen.class.getResourceAsStream(resource)) {
            return in == null ? null : new Image(in);
        } catch (Exception e) {
            return null;
        }
    }
}
