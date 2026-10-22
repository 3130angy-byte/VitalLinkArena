package org.example.behavior;

import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import com.github.cfogrady.vb.dim.sprite.SpriteData;

import org.example.animation.DimAnimationPlayer;
import org.example.arena.ArenaConfig;
import org.example.arena.ArenaEngine;
import org.example.arena.ArenaFighter;
import org.example.arena.ArenaScreen;
import org.example.lab.BattleHistory;
import org.example.lab.Digidex;
import org.example.lab.LabStorage;
import org.example.lab.LabWindow;
import org.example.online.Protocol;
import org.example.animation.DimSpriteSet;
import org.example.animation.SpriteRole;
import org.example.animation.TeleportAnimator;
import org.example.animation.VPetAnimations;
import org.example.animation.VPetStageTier;
import org.example.battle.AttackSpriteResolver;
import org.example.battle.BattleEngine;
import org.example.battle.BattlePresentationScreen;
import org.example.battle.PortalTransitionScene;
import org.example.battle.RivalDimPool;
import org.example.battle.VitalRewards;
import org.example.chat.AppPaths;
import org.example.dim.VsDimWriter;
import org.example.chat.DigimonVoice;
import org.example.chat.PhraseBank;
import org.example.chat.PhrasePicker;
import org.example.chat.GameSettings;
import org.example.online.client.LobbyDigimon;
import org.example.online.client.OfficialBattleResult;
import org.example.online.client.LobbyWindow;
import org.json.JSONObject;
import org.example.chat.SpeechBubble;
import org.example.dim.DimSpriteImageFactory;
import org.example.dim.NameSpriteReader;
import org.example.dim.DimVPetData;
import org.example.dim.VsDimData;
import org.example.interaction.VPetClickToMoveController;
import org.example.sound.Sfx;
import org.example.sound.Sounds;
import org.example.ui.DigimonInfoPanel;
import org.example.ui.VPetMenu;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Un Digimon en el escritorio (sin crianza). Llega SOLO
 * desde una VS DIM (decisión del usuario), en forma fija: sin huevo,
 * hambre, suciedad, malestar, evolución, Jogress ni muerte. Conserva los
 * datos del Digimon (sprites, stats, atributo, Activity Type, especie,
 * nombre, edad, Vital Values, Power Trophies) y Batalla aleatoria.
 */
public class DigimonInstance {

    private static final int CANVAS_WIDTH = 64;
    private static final int CANVAS_HEIGHT = 56;

    private final String instanceId;
    private final Path sourceBinPath;
    private final DigimonAge age;
    private DigimonProgress progress;

    private int currentSlot;
    private VPetStageTier currentStage;
    private String displayName;
    private Image nameSpriteImage;
    /** Especie escrita al importarlo (prellenada con la que se lee del sprite NAME), o null = "Digimon". */
    private String recognizedSpecies;

    private Stage stage;
    private ImageView imageView;
    private DimVPetData dimData;
    private DimAnimationPlayer animationPlayer;
    private VPetMovementController movementController;
    private VPetClickToMoveController clickToMove;
    /** Entrando o saliendo por el portal: nada más puede mover la ventana. */
    private boolean teleporting = false;
    private CompanionController companion;
    private VPetMenu menu;
    private final DigimonVoice voice = new DigimonVoice();
    private SpeechBubble speechBubble;

    private DigimonRegistry registry;
    private final AttackSpriteResolver attackSpriteResolver = new AttackSpriteResolver();
    /** Aviso a Main cuando el Digimon se retiró (vuelve la pantalla de inicio). */
    private Consumer<DigimonInstance> onRetired;
    private boolean retiring = false;
    /** Cápsula del Laboratorio de la que salió (todo Digimon del escritorio tiene una). */
    private String capsuleId;
    /**
     * Premio del campeón de un torneo del VS Online (pedido del usuario, 2026-10-18): lo tiene (ganó un torneo) y si
     * se luce al DOBLE ahora (se prende y se apaga en el menú V-PET). Se guarda en su cápsula.
     */
    private boolean prizeOwned, prizeOn;
    /** Está dentro del portal (batalla, ARENA o sala online). */
    private boolean away = false;
    /** Lo reemplazaron desde el Laboratorio mientras estaba fuera: se va al volver. */
    private Runnable pendingDismiss;
    /** Está en la sala del VS Online siguiendo a tu avatar (caso particular de away). */
    private boolean inLobby = false;
    /** A dónde vuelve en el escritorio al salir de la sala. */
    private double lobbyReturnX, lobbyReturnY;
    /** Nace directo en la sala (reemplazo desde la PC): sin animación de llegada al escritorio. */
    private boolean spawnIntoLobby = false;
    /** Está dentro de una ARENA 2 vs 2 como compañero (puesto 2); vuelve a donde estaba. */
    private boolean inArena = false;
    private double arenaReturnX, arenaReturnY;
    /** Comentario de una pelea que terminó mientras estaba fuera: lo dice al volver al escritorio. */
    private String pendingEvent;
    private Map<String, String> pendingVars = Map.of();
    /** Comentario suelto cada 20-40 min (banco "pensamiento"). */
    private PauseTransition thoughtTimer;

    private DigimonInstance(String instanceId, Path sourceBinPath, int importedAgeDays) {
        this.instanceId = instanceId;
        this.sourceBinPath = sourceBinPath;
        this.age = new DigimonAge(importedAgeDays);
    }

    /** Digimon traído del Vital Bracelet mediante una VS DIM; el slot lo dice el propio archivo. */
    public static DigimonInstance fromVsDim(Path vsDimPath, int ageDays) {
        return new DigimonInstance(UUID.randomUUID().toString(), vsDimPath, ageDays);
    }

    /** Especie escrita al importarlo (prellenada con la que se lee del sprite NAME; vacía = "Digimon"). */
    public void setTypedSpecies(String species) {
        this.recognizedSpecies = species == null || species.isBlank() ? null : species.trim();
    }

    public void setOnRetired(Consumer<DigimonInstance> onRetired) {
        this.onRetired = onRetired;
    }

    public void setCapsuleId(String capsuleId) { this.capsuleId = capsuleId; }
    public String getCapsuleId() { return capsuleId; }

    /**
     * Sale del escritorio y vuelve a su cápsula del Laboratorio, SIN generar
     * archivo para el VB (eso es RETIRAR). Guarda su récord pendiente. Si
     * está dentro del portal (sala online, batalla), se va al volver de ahí.
     */
    public void dismiss(Runnable onGone) {
        if (capsuleId != null && progress != null) LabStorage.saveProgress(capsuleId, progress.toProperties());
        Runnable finish = () -> {
            stopAll();
            dispose();
            onGone.run();
        };
        if (inLobby) {
            // En la sala: la sala lo anima entrando a su portal (LobbyWindow.beginPortalSwap);
            // aquí su ventana ya está vacía, así que se cierra al instante.
            inLobby = false;
            away = false;
            stopAll();
            dispose();
            onGone.run();
            return;
        }
        if (away) {
            // Está en una pelea: el equipo cambia YA (el nuevo ocupa su
            // puesto), y esta ventana, que está escondida, se cierra cuando vuelva.
            pendingDismiss = () -> {
                stopAll();
                dispose();
            };
            onGone.run();
            return;
        }
        retiring = true;
        companion.setMenuOpen(true);
        stopAll();
        animationPlayer.stop();
        voice.react("reemplazo", playerVars());
        enterPortal(new TeleportAnimator(), animationPlayer.getSpriteSet(), finish);
    }

    /**
     * Entrada al portal. Primero CORTA el paseo, el clic-para-mover o la
     * acción que estuviera en curso: antes seguían moviendo la ventana a la
     * vez que el portal y el Digimon se deslizaba hasta la esquina y volvía
     * (bug desde 0.0.2). Si venía moviéndose, se queda en IDLE un momento y
     * recién entonces se calcula dónde aparece el portal (TeleportAnimator).
     */
    private void enterPortal(TeleportAnimator teleport, DimSpriteSet sprites, Runnable onComplete) {
        boolean wasMoving = movementController.isMoving();
        movementController.stopMovement();
        clickToMove.cancel();
        animationPlayer.stop();
        teleporting = true;
        teleport.playEnter(imageView, stage, sprites, currentStage, wasMoving, () -> {
            teleporting = false;
            onComplete.run();
        });
    }

    /** Salida del portal; mientras dura, el clic no lo mueve. */
    private void exitPortal(TeleportAnimator teleport, DimSpriteSet sprites, double x, double y, Runnable onComplete) {
        clickToMove.cancel();
        teleporting = true;
        teleport.playExit(imageView, stage, sprites, x, y, () -> {
            teleporting = false;
            onComplete.run();
        });
    }

    /** Vuelve del portal: normalmente con la animación de salida; si lo reemplazaron mientras tanto, se va. */
    private void comeBack(Runnable exitAnimation) {
        away = false;
        if (pendingDismiss != null) {
            Runnable finish = pendingDismiss;
            pendingDismiss = null;
            finish.run();
            return;
        }
        exitAnimation.run();
    }

    public boolean isAway() { return away; }

    public void spawn(double x, double y, DigimonRegistry registry) throws Exception {
        this.registry = registry;
        this.dimData = DimVPetData.load(sourceBinPath, 0);
        if (!dimData.isVsDim()) {
            throw new IllegalArgumentException(sourceBinPath.getFileName() + " no es una VS DIM.");
        }
        VsDimData vs = dimData.getVsData();
        this.currentSlot = vs.slot();
        this.progress = new DigimonProgress(vs.vitalValues(), vs.powerTrophies());
        // Récord pendiente guardado en su cápsula (sobrevive a reemplazos y a cerrar el programa).
        if (capsuleId != null) progress.restore(LabStorage.loadProgress(capsuleId));
        if (capsuleId != null) {
            prizeOwned = "true".equals(LabStorage.loadValue(capsuleId, "premio.torneo"));
            prizeOn = prizeOwned && !"false".equals(LabStorage.loadValue(capsuleId, "premio.visible"));
        }

        this.speechBubble = new SpeechBubble();

        imageView = new ImageView();
        // Con el premio encendido, al doble: sin suavizado, cada píxel del sprite queda en 2x2 (sigue siendo pixel art).
        imageView.setFitWidth(CANVAS_WIDTH * size());
        imageView.setFitHeight(CANVAS_HEIGHT * size());
        imageView.setPreserveRatio(false);
        imageView.setSmooth(false);
        org.example.animation.PixelScale.crisp(imageView); // al doble (premio): cada píxel en 2x2 de verdad

        Pane root = new Pane(imageView);
        root.setBackground(Background.EMPTY);
        Scene scene = new Scene(root, CANVAS_WIDTH * size(), CANVAS_HEIGHT * size());
        scene.setFill(Color.TRANSPARENT);

        stage = new Stage();
        org.example.ui.DesktopLayer.attach(stage); // sin botón en la barra de tareas; sigue a la vista con "Mostrar escritorio"
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setScene(scene);
        stage.setX(x);
        stage.setY(y);

        voice.addOnBubbleMessageListener(text -> speechBubble.show(text, stage));

        stage.setOpacity(0);
        stage.show();
        PauseTransition reveal = new PauseTransition(Duration.millis(120));
        reveal.setOnFinished(e -> stage.setOpacity(1));
        reveal.play();

        start();
    }

    private void start() {
        DimSpriteSet sprites = buildSpriteSetForSlot(dimData, currentSlot);
        currentStage = sprites.getStage();

        this.displayName = getDisplayName();
        setUpPhrases();

        animationPlayer = new DimAnimationPlayer(imageView, sprites);

        movementController = new VPetMovementController(stage, imageView, animationPlayer, currentStage);
        clickToMove = new VPetClickToMoveController(stage, movementController);
        companion = new CompanionController(stage, movementController, clickToMove, currentStage);
        menu = new VPetMenu(stage, companion, currentStage,
                this::battleRandomAction, this::vsOnlineAction, this::openInfoPanel,
                this::retireAction, this::arenaAction);
        menu.setPrize(() -> prizeOwned, () -> prizeOn, this::togglePrize);

        speechBubble.setDigimonName(displayName);
        this.nameSpriteImage = DimSpriteImageFactory.toNativeImage(dimData.getSpritesForSlot(currentSlot).get(0));
        Digidex.seen(dimData.getSpritesForSlot(currentSlot).get(0), recognizedSpecies);
        speechBubble.setDigimonNameSpriteImage(nameSpriteImage);

        imageView.setOnMouseClicked(e -> {
            if (teleporting) return; // en plena animación del portal no se le manda nada
            if (e.getButton() == MouseButton.PRIMARY) {
                clickToMove.onPetClicked();
            } else if (e.getButton() == MouseButton.SECONDARY) {
                menu.toggle(stage); // se abre al costado del Digimon (ver VPetMenu)
            }
        });

        // Llega al escritorio saliendo de un portal (pedido del usuario); recién
        // entonces empieza a pasear. Mientras tanto no se le puede mandar nada.
        companion.setMenuOpen(true);
        if (spawnIntoLobby) {
            // Reemplazó al puesto 1 mientras estaba en la sala: aparece ALLÁ (por el portal de
            // la sala), no aquí. Su ventana queda vacía hasta que cierres la sala.
            imageView.setVisible(false);
            lobbyReturnX = stage.getX();
            lobbyReturnY = stage.getY();
            away = true;
            inLobby = true;
        } else {
            exitPortal(new TeleportAnimator(), sprites, stage.getX(), stage.getY(), () -> {
                animationPlayer.play(VPetAnimations.idle(currentStage), 2.0);
                companion.start();
                companion.setMenuOpen(false);
                voice.react("aparecer", playerVars());
            });
        }
        scheduleThought();
    }

    // ---------- Banco de frases ----------

    /**
     * Su banco de frases según su atributo, etapa y Activity Type. Las frases de "primera vez" ya dichas
     * se guardan en su cápsula y no vuelven.
     */
    private void setUpPhrases() {
        var entry = dimData.getCard().getCharacterStats().getCharacterEntries().get(currentSlot);
        int dimStage = dimData.getStageForSlot(currentSlot);
        voice.setPhrasePicker(new PhrasePicker(PhraseBank.packaged(), entry.getAttribute(), dimStage, entry.getType(),
                new Random(), firstTimeMemory()));
    }

    /** Las frases de "primera vez" ya dichas, en su cápsula (una vez por vida, no por sesión). */
    private PhrasePicker.Memory firstTimeMemory() {
        if (capsuleId == null) return PhrasePicker.inMemory();
        return new PhrasePicker.Memory() {
            @Override
            public Set<String> firstTimeDone() {
                String saved = LabStorage.loadValue(capsuleId, "frases.primeraVez");
                return saved.isBlank() ? new HashSet<>() : new HashSet<>(Arrays.asList(saved.split(",")));
            }

            @Override
            public void saveFirstTimeDone(Set<String> groups) {
                LabStorage.saveValue(capsuleId, "frases.primeraVez", String.join(",", new TreeSet<>(groups)));
            }
        };
    }

    private Map<String, String> playerVars() {
        String player = GameSettings.playerName();
        return player == null || player.isBlank() ? Map.of() : Map.of("jugador", player.strip());
    }

    /**
     * Un comentario suelto cada 20-40 minutos (banco "pensamiento"), nunca si está fuera, ocupado o ya
     * está hablando.
     */
    private void scheduleThought() {
        thoughtTimer = new PauseTransition(Duration.minutes(20 + new Random().nextInt(21)));
        thoughtTimer.setOnFinished(e -> {
            boolean busy = away || teleporting || retiring || inLobby || companion.isMenuOpen() || speechBubble.isShowing();
            if (!busy) voice.react("pensamiento", playerVars());
            scheduleThought();
        });
        thoughtTimer.play();
    }

    /**
     * El comentario de una pelea: si está en el escritorio, ya; si está fuera (en la pelea o en la
     * sala), al volver, con la ventana ya a la vista.
     */
    private void reactAfterBattle(String event, Map<String, String> vars) {
        if (away || inLobby) {
            pendingEvent = event;
            pendingVars = vars;
        } else {
            voice.react(event, vars);
        }
    }

    /** Dice el comentario pendiente de una pelea, si hay. true si había uno. */
    private boolean sayPendingReaction() {
        if (pendingEvent == null) return false;
        String event = pendingEvent;
        pendingEvent = null;
        voice.react(event, pendingVars);
        return true;
    }

    /** De vuelta de la sala: comenta la última pelea de allá o, si no peleó, que volvió. */
    private void afterLobby() {
        if (!sayPendingReaction()) voice.react("volver_de_sala", playerVars());
    }

    /** "DYNASMON" (fuente del VB en mayúsculas) -> "Dynasmon"; "MagnaKidmon" queda igual. */
    private static String prettyName(String name) {
        if (name == null || name.isBlank() || !name.equals(name.toUpperCase())) return name;
        StringBuilder sb = new StringBuilder();
        for (String w : name.toLowerCase().split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    // ---------- Panel "Digimon" ----------

    private void openInfoPanel() {
        Image portraitImage = animationPlayer.getSpriteSet().get(SpriteRole.IDLE_1);
        var entry = dimData.getCard().getCharacterStats().getCharacterEntries().get(currentSlot);
        new DigimonInfoPanel(stage).show(stage.getX(), stage.getY() - 220, nameSpriteImage, portraitImage,
                attributeLabel(entry.getAttribute()), activityTypeLabel(entry.getType()), age.getAgeDays(),
                new DigimonInfoPanel.BattleStats(entry.getDp(), entry.getHp(), entry.getAp(), progress.getPowerTrophies()));
    }

    private String attributeLabel(int attribute) {
        return switch (attribute) {
            case 1 -> "Virus";
            case 2 -> "Data";
            case 3 -> "Vaccine";
            case 4 -> "Free";
            default -> "Null";
        };
    }

    private String activityTypeLabel(int activityType) {
        return switch (activityType) {
            case 0 -> "Estoico";
            case 1 -> "Activo";
            case 2 -> "Normal";
            case 3 -> "Interior";
            case 4 -> "Perezoso";
            default -> "Desconocido";
        };
    }

    // ---------- Batalla aleatoria ----------

    /**
     * La VS DIM solo trae los sprites de su propio Digimon, así que los rivales
     * salen de la carpeta de DIM cards elegida en Laboratorio > RIVALES
     * (RivalDimPool). Esas DIM no se crían: solo aportan rivales.
     */
    private void battleRandomAction() {
        Optional<DimVPetData> rivalCardOpt = rivalCard();
        if (rivalCardOpt.isEmpty()) return;
        DimVPetData rivalCard = rivalCardOpt.get();

        companion.setMenuOpen(true);
        animationPlayer.stop();

        double originalX = stage.getX();
        double originalY = stage.getY();

        TeleportAnimator teleport = new TeleportAnimator();
        DimSpriteSet currentSprites = animationPlayer.getSpriteSet();

        away = true;
        enterPortal(teleport, currentSprites, () -> {
            BattleEngine engine = new BattleEngine();
            int enemySlot = engine.pickOpponentSlot(rivalCard, dimData.getStageForSlot(currentSlot), -1);
            Digidex.seen(rivalCard.getSpritesForSlot(enemySlot).get(0), null);
            BattleEngine.Combatant player = engine.combatantFromSlot(dimData, currentSlot);
            BattleEngine.Combatant enemy = engine.combatantFromSlot(rivalCard, enemySlot);
            BattleEngine.BattleResult result = engine.fight(player, enemy, currentSlot, enemySlot);

            DimSpriteSet enemySprites = buildSpriteSetForSlot(rivalCard, enemySlot);

            // La escena es pixel art de 480x270: la ventana mide un múltiplo ENTERO (x2, x3...) para
            // que todos los píxeles queden del mismo tamaño (960x540 en una pantalla de 768 o 1080).
            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            int pixel = BattlePresentationScreen.scaleFor(screenBounds.getWidth() * 0.95, screenBounds.getHeight() * 0.76);
            double sceneW = BattlePresentationScreen.SCENE_W * pixel;
            double sceneH = BattlePresentationScreen.SCENE_H * pixel;

            PortalTransitionScene portal = new PortalTransitionScene(sceneW, sceneH, true);
            BattlePresentationScreen presentation = new BattlePresentationScreen(attackSpriteResolver);
            portal.setContent(presentation.buildNode(
                    currentSprites.get(SpriteRole.IDLE_1), enemySprites.get(SpriteRole.IDLE_1),
                    nameSpriteImage, nativeNameImage(rivalCard, enemySlot),
                    player, enemy, sceneW, sceneH));
            presentation.setBig(prizeOn, false); // premio de campeón: al doble en pixel art
            int rivalStage = rivalCard.getStageForSlot(enemySlot);
            // Su especie, si se puede leer del sprite NAME (para el {rival} del banco de frases).
            String rivalName = NameSpriteReader.read(rivalCard.getSpritesForSlot(enemySlot).get(0))
                    .map(NameSpriteReader.Result::text).map(DigimonInstance::prettyName).orElse(null);
            portal.open(() -> presentation.play(result, currentSprites, enemySprites, player, enemy, () -> {
                presentation.dispose();
                applyBattleResult(result, rivalStage, rivalName);

                portal.close(() -> comeBack(() ->
                        exitPortal(teleport, animationPlayer.getSpriteSet(), originalX, originalY, () -> {
                            reactToBattle(result.draw ? null : result.won, true);
                            companion.setMenuOpen(false);
                            sayPendingReaction(); // el comentario, ya de vuelta y con la pose
                        })
                ));
            }));
        });
    }

    /**
     * Una DIM card normal de la carpeta de rivales (RivalDimPool). Si falta la
     * carpeta se elige aquí mismo (o en Laboratorio > RIVALES).
     * Vacío = no hay rivales (ya se le avisó al usuario).
     */
    private Optional<DimVPetData> rivalCard() {
        Optional<DimVPetData> card = RivalDimPool.pickRandomCard();
        if (card.isEmpty()) {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Elige una carpeta con DIM cards normales (.bin) para los rivales");
            java.io.File folder = chooser.showDialog(stage);
            if (folder == null) return Optional.empty();
            GameSettings.saveRivalFolder(folder.getAbsolutePath());
            card = RivalDimPool.pickRandomCard();
            if (card.isEmpty()) {
                voice.announce("En esa carpeta no encontré DIM cards normales para pelear.");
                return Optional.empty();
            }
        }
        return card;
    }

    // ---------- ARENA (2 vs 2) ----------

    /**
     * Tercer modo de combate, inspirado en la app Vital Bracelet Arena
     * (decisiones del usuario): 2 vs 2 contra la máquina, con ataque de
     * números, defensa con barra, cambio y W-ATTACK (reglas en ArenaEngine /
     * ArenaConfig). Tu equipo = este Digimon + el otro del escritorio si es
     * Child o superior; si no, un COMPAÑERO prestado de una DIM card de la
     * carpeta de rivales. El rival: 2 Digimon de esas DIM cards. NO cuenta
     * para el récord ni para el reporte al retirar (como en la app real).
     */
    private void arenaAction() {
        ArenaConfig config = ArenaConfig.load();
        BattleEngine engine = new BattleEngine();
        int myStage = dimData.getStageForSlot(currentSlot);

        // El equipo sale de los PUESTOS del escritorio (1 = principal, 2 = secundario), no de
        // quién recibió el clic; los Baby no pelean y se saltan.
        List<DigimonInstance> fighters = registry.getActiveInstances().stream()
                .filter(d -> d.getDimData().getStageForSlot(d.getCurrentSlot()) >= 2).toList();
        DigimonInstance first = fighters.isEmpty() ? this : fighters.get(0);
        ArenaFighter me = ArenaFighter.fromDim(first.getDimData(), first.getCurrentSlot(), first.getDisplayName(), false, config)
                .big(first.prizeOn);
        ArenaFighter partner = null;
        if (fighters.size() >= 2) {
            DigimonInstance second = fighters.get(1);
            partner = ArenaFighter.fromDim(second.getDimData(), second.getCurrentSlot(), second.getDisplayName(), false, config)
                    .big(second.prizeOn);
        }

        // Sin otro Digimon en el escritorio: primero una de tus cápsulas del Laboratorio (Child+).
        if (partner == null) {
            List<LabStorage.Capsule> capsules = LabStorage.list().stream()
                    .filter(c -> c.data().character().stage() >= 2 && !c.file().equals(first.getSourceBinPath())).toList();
            if (!capsules.isEmpty()) {
                LabStorage.Capsule c = capsules.get(new java.util.Random().nextInt(capsules.size()));
                try {
                    DimVPetData capsuleData = DimVPetData.load(c.file(), 0);
                    partner = ArenaFighter.fromDim(capsuleData, c.data().slot(), c.species(), true, config);
                    Digidex.seen(capsuleData.getSpritesForSlot(c.data().slot()).get(0), c.species());
                } catch (Exception e) {
                    System.out.println("[ARENA] No se pudo usar la cápsula " + c.id() + ": " + e.getMessage());
                }
            }
        }

        ArenaFighter[] cpu = new ArenaFighter[2];
        for (int i = 0; i < (partner == null ? 3 : 2); i++) {
            Optional<DimVPetData> card = rivalCard();
            if (card.isEmpty()) return;
            int slot = engine.pickOpponentSlot(card.get(), myStage, -1);
            Digidex.seen(card.get().getSpritesForSlot(slot).get(0), null);
            if (i < 2) {
                cpu[i] = ArenaFighter.fromDim(card.get(), slot, "Rival " + (i + 1), false, config);
            } else {
                partner = ArenaFighter.fromDim(card.get(), slot, "Compañero", true, config);
            }
        }
        ArenaFighter[] team = {me, partner};

        // Por el portal entra el PUESTO 1 y, si su compañero es el puesto 2 del escritorio, este
        // lo sigue por detrás y cruza el MISMO portal (pedido del usuario); al final salen los dos
        // por el mismo portal.
        DigimonInstance leader = first.away ? this : first;
        DigimonInstance follower = fighters.size() >= 2 && fighters.get(1) != leader
                && !fighters.get(1).away && !fighters.get(1).teleporting ? fighters.get(1) : null;
        TeleportAnimator teleport = new TeleportAnimator();
        if (follower != null) teleport.keepPortalOpen();

        double leaderX = leader.stage.getX(), leaderY = leader.stage.getY();
        leader.companion.setMenuOpen(true);
        leader.animationPlayer.stop();
        leader.away = true;
        if (follower != null) follower.startFollowing(leader);

        Runnable openArena = () -> new ArenaScreen(new ArenaEngine(config, new java.util.Random(), team, cpu)).open(won -> {
            if (follower != null) teleport.keepPortalOpen(); // salen los dos por el mismo portal
            leader.comeBack(() -> leader.exitPortal(teleport, leader.animationPlayer.getSpriteSet(), leaderX, leaderY, () -> {
                leader.animationPlayer.play(VPetAnimations.idle(leader.currentStage), 2.0);
                if (won != null) { // null = cerró la ventana a media pelea
                    BattleHistory.add(leader.displayName + " + " + team[1].name, "ARENA 2 vs 2",
                            "rivales " + LabWindow.stageName(cpu[0].stage) + " y " + LabWindow.stageName(cpu[1].stage),
                            won ? "Victoria" : "Derrota", "no cuenta");
                }
                // La pose de victoria o derrota recién cuando salieron LOS DOS, con un solo sonido.
                Consumer<Boolean> bothOut = followerOut -> {
                    leader.reactToBattle(won, true);
                    if (followerOut) follower.reactToBattle(won, false);
                    leader.companion.setMenuOpen(false);
                    if (won != null) leader.reactOnly(won ? VitalRewards.BattleOutcome.WIN : VitalRewards.BattleOutcome.LOSS,
                            team[1].name);
                };
                if (follower != null) follower.leaveArenaThrough(teleport, bothOut);
                else bothOut.accept(false);
            }));
        });
        leader.enterPortal(teleport, leader.animationPlayer.getSpriteSet(), () -> {
            if (follower == null) openArena.run();
            else follower.followIntoPortal(teleport, openArena);
        });
    }

    /**
     * ARENA local: el compañero (puesto 2) deja de pasear y camina detrás del
     * principal, hacia el centro de la pantalla donde se abrirá el portal.
     */
    private void startFollowing(DigimonInstance leader) {
        companion.setMenuOpen(true);
        clickToMove.cancel();
        teleporting = true; // camino al portal: el clic no lo desvía
        away = true;
        inArena = true;
        arenaReturnX = stage.getX();
        arenaReturnY = stage.getY();
        double center = TeleportAnimator.centerX();
        double fromSide = Math.signum(leader.stage.getX() - center);
        if (fromSide == 0) fromSide = -1;
        // Se queda del lado por donde viene el principal, un poco más atrás.
        movementController.walkTo(center + fromSide * 110, TeleportAnimator.bottomY());
    }

    /** Cruza el portal que dejó abierto el principal (deja de caminar primero: nunca dos animaciones a la vez). */
    private void followIntoPortal(TeleportAnimator teleport, Runnable onEntered) {
        movementController.stopMovement();
        clickToMove.cancel();
        animationPlayer.stop();
        teleporting = true;
        teleport.playFollowIntoOpenPortal(imageView, stage, animationPlayer.getSpriteSet(), () -> {
            teleporting = false;
            onEntered.run();
        });
    }

    /**
     * ARENA local terminada: sale detrás del principal por el mismo portal (que se cierra tras él).
     * {@code onOut} recibe true cuando ya está en el escritorio, o false si lo habían reemplazado
     * mientras peleaba (se va sin volver).
     */
    private void leaveArenaThrough(TeleportAnimator teleport, Consumer<Boolean> onOut) {
        inArena = false;
        boolean replaced = pendingDismiss != null;
        comeBack(() -> {
            clickToMove.cancel();
            teleporting = true;
            teleport.playExitThroughOpenPortal(imageView, stage, animationPlayer.getSpriteSet(), () -> {
                teleporting = false;
                backOnDesktop();
                onOut.accept(true);
            });
        });
        if (replaced) onOut.accept(false);
    }

    /**
     * ARENA online (pedido del usuario): el puesto 1 ya está en la sala; el
     * puesto 2, que está en el escritorio, cruza su propio portal antes de que
     * empiece la pelea. {@code onEntered} = empezar la pelea.
     */
    public void goToArena(Runnable onEntered) {
        if (away || teleporting) {
            onEntered.run();
            return;
        }
        companion.setMenuOpen(true);
        arenaReturnX = stage.getX();
        arenaReturnY = stage.getY();
        away = true;
        inArena = true;
        enterPortal(new TeleportAnimator(), animationPlayer.getSpriteSet(), onEntered);
    }

    /**
     * Terminó la ARENA online: solo el puesto 2 sale de su portal al escritorio, a donde estaba, y
     * ya afuera hace su pose (sin sonido: suena una sola vez, en la sala). {@code whenOut} = ya volvió
     * (también si no estaba en la ARENA o lo habían reemplazado). {@code won} null = no terminó.
     */
    public void returnFromArena(Boolean won, Runnable whenOut) {
        if (!inArena) {
            whenOut.run();
            return;
        }
        inArena = false;
        boolean replaced = pendingDismiss != null;
        comeBack(() -> exitPortal(new TeleportAnimator(), animationPlayer.getSpriteSet(), arenaReturnX, arenaReturnY, () -> {
            backOnDesktop();
            reactToBattle(won, false);
            whenOut.run();
        }));
        if (replaced) whenOut.run();
    }

    /** ARENA online cerrada: vuelven por su portal los que fueron desde el escritorio; luego, {@code allBack}. */
    private void arenaTeamBack(Boolean won, Runnable allBack) {
        List<DigimonInstance> returning = List.copyOf(registry.getActiveInstances()).stream()
                .filter(DigimonInstance::isInArena).toList();
        if (returning.isEmpty()) {
            allBack.run();
            return;
        }
        int[] pending = {returning.size()};
        for (DigimonInstance d : returning) {
            d.returnFromArena(won, () -> {
                if (--pending[0] == 0) allBack.run();
            });
        }
    }

    public boolean isInArena() { return inArena; }

    // ---------- VS Online ----------

    /**
     * Batalla > VS Online: el Digimon entra al portal (misma animación que la
     * Batalla aleatoria), se abre la sala del VS Online donde se maneja el
     * avatar, y el Digimon aparece ahí siguiéndolo. Al cerrar la sala, el
     * Digimon vuelve al escritorio con la animación de salida.
     * Servidor, puerto y nombre de jugador: pantalla de inicio (vitallink.properties).
     */
    private void vsOnlineAction() {
        if (LobbyWindow.isAnyOpen()) return;
        // A la sala entra SIEMPRE el puesto 1 (es quien te sigue ahí), aunque el menú sea del puesto 2.
        DigimonInstance first = registry.primary().orElse(this);
        if (first != this) {
            if (first.away) {
                voice.announce(first.getDisplayName() + " (puesto 1) todavía no vuelve; espera a que regrese para entrar a la sala.");
                return;
            }
            first.vsOnlineAction();
            return;
        }

        companion.setMenuOpen(true);
        animationPlayer.stop();
        DimSpriteSet currentSprites = animationPlayer.getSpriteSet();

        lobbyReturnX = stage.getX();
        lobbyReturnY = stage.getY();
        voice.react("ir_a_sala", playerVars());
        away = true;
        inLobby = true;
        enterPortal(new TeleportAnimator(), currentSprites, () -> {
            // En la sala va TU EQUIPO: el puesto 1 te sigue y pelea las batallas tipo VB; el
            // puesto 2 viaja como compañero para el 2 vs 2. Se reenvía si lo cambias en la PC.
            // Al cerrar vuelve QUIEN ESTÉ en la sala en ese momento (pudo cambiar desde la PC).
            LobbyWindow lobby = new LobbyWindow(new Stage(), GameSettings.onlineHost(), GameSettings.onlinePort(),
                    GameSettings.playerName(), 0, teamPayload(), "VS Online - " + GameSettings.playerName(),
                    () -> List.copyOf(registry.getActiveInstances()).stream()
                            .filter(d -> d.isInLobby() && !d.teleporting).forEach(DigimonInstance::returnFromLobby));
            lobby.setTeamSupplier(this::teamPayload);
            // Cada Batalla Oficial cuenta para quien la peleó: el puesto 1 del momento.
            lobby.setOnBattleResult(r -> registry.primary().orElse(this).recordOnlineBattle(r));
            // ARENA 2 vs 2 online: tu puesto 2 (en el escritorio) cruza su portal antes de la pelea
            // y, al terminar, solo él vuelve a salir por su portal (pedido del usuario); ya afuera,
            // él y el puesto 1 de la sala hacen su pose de victoria o derrota.
            lobby.setArenaHooks(
                    start -> registry.secondary().filter(d -> !d.isInLobby()).ifPresentOrElse(
                            second -> second.goToArena(start), start),
                    this::arenaTeamBack);
            // Torneo ganado: el premio es para el puesto 1 de la sala y, en la ARENA, también para el puesto 2.
            LobbyWindow.setOnTournamentPrize(mode -> {
                registry.primary().orElse(this).grantPrize();
                if (org.example.online.Protocol.MODE_ARENA.equals(mode)) registry.secondary().ifPresent(DigimonInstance::grantPrize);
            });
            lobby.open();
        });
    }

    /** Sale de la sala y vuelve al escritorio por el portal, al lugar desde donde entró. */
    public void returnFromLobby() {
        leaveLobbyTo(lobbyReturnX, lobbyReturnY);
    }

    /** Sale de la sala y aparece en el escritorio por un portal nuevo, caminando hacia (x, y). */
    private void leaveLobbyTo(double x, double y) {
        if (!inLobby) return;
        inLobby = false;
        comeBack(() -> exitPortal(new TeleportAnimator(), animationPlayer.getSpriteSet(), x, y, () -> {
            backOnDesktop();
            afterLobby();
        }));
    }

    /**
     * Intercambio de puestos 1 ⇄ 2 con la sala abierta: sale de la sala por
     * el portal del escritorio que dejó ABIERTO el otro al entrar (el portal
     * no se cierra ni aparece en otro lado). Si ya no estaba en la sala, solo
     * se cierra ese portal.
     */
    public void leaveLobbyThrough(TeleportAnimator openPortal) {
        if (!inLobby) {
            openPortal.closeOpenPortal(null);
            return;
        }
        inLobby = false;
        comeBack(() -> {
            clickToMove.cancel();
            teleporting = true;
            openPortal.playExitThroughOpenPortal(imageView, stage, animationPlayer.getSpriteSet(), () -> {
                teleporting = false;
                backOnDesktop();
                afterLobby();
            });
        });
    }

    private void backOnDesktop() {
        animationPlayer.play(VPetAnimations.idle(currentStage), 2.0);
        companion.start(); // si llegó directo a la sala, todavía no paseaba
        companion.setMenuOpen(false);
    }

    /**
     * Del escritorio a la sala (intercambio de puestos con la sala abierta):
     * entra a un portal que QUEDA ABIERTO y, cuando desaparece,
     * {@code onEntered} recibe ese portal para que el otro salga por él.
     */
    public void goToLobby(Consumer<TeleportAnimator> onEntered) {
        companion.setMenuOpen(true);
        lobbyReturnX = stage.getX();
        lobbyReturnY = stage.getY();
        away = true;
        inLobby = true;
        TeleportAnimator portal = new TeleportAnimator().keepPortalOpen();
        enterPortal(portal, animationPlayer.getSpriteSet(), () -> onEntered.accept(portal));
    }

    /** Llega DIRECTO a la sala (reemplazo del puesto 1 desde la PC): no aparece en el escritorio. */
    public void setSpawnIntoLobby(boolean spawnIntoLobby) {
        this.spawnIntoLobby = spawnIntoLobby;
    }

    public boolean isInLobby() { return inLobby; }

    /** Mensaje "digimon" con el equipo actual: puesto 1 + puesto 2 (si hay) como compañero. */
    private JSONObject teamPayload() {
        DigimonInstance first = registry.primary().orElse(this);
        DigimonInstance second = registry.secondary().orElse(null);
        JSONObject team = LobbyDigimon.teamPayload(first.getDimData().getVsData(), first.speciesForOnline(),
                second == null ? null : second.getDimData().getVsData(),
                second == null ? null : second.speciesForOnline());
        // v12: el premio encendido se ve al doble para todos (sala, coliseo y ARENA).
        team.put("big", first.prizeOn);
        // v13: quién es para el ranking (su cápsula del Laboratorio; nunca se reparte a los demás jugadores).
        if (first.capsuleId != null) team.put("uid", first.capsuleId);
        JSONObject partner = team.optJSONObject("partner");
        if (partner != null && second != null) partner.put("big", second.prizeOn);
        return team;
    }

    // ---------- Premio del campeón de un torneo (2026-10-18) ----------

    private int size() {
        return prizeOn ? 2 : 1;
    }

    /** Ganó un torneo del VS Online (él, o su equipo en la ARENA): recibe el premio, ya encendido. */
    public void grantPrize() {
        prizeOwned = true;
        prizeOn = true;
        if (capsuleId != null) {
            LabStorage.saveValue(capsuleId, "premio.torneo", "true");
            LabStorage.saveValue(capsuleId, "premio.visible", "true");
        }
        applySize();
        if (menu != null) menu.setPrize(() -> prizeOwned, () -> prizeOn, this::togglePrize);
    }

    /** Menú V-PET > GRANDE: SÍ / NO. Cambia su tamaño en el escritorio y, si está en la sala, para todos. */
    private void togglePrize() {
        if (!prizeOwned) return;
        prizeOn = !prizeOn;
        if (capsuleId != null) LabStorage.saveValue(capsuleId, "premio.visible", String.valueOf(prizeOn));
        applySize();
        if (menu != null) menu.setPrize(() -> prizeOwned, () -> prizeOn, this::togglePrize); // la casilla dice cómo quedó
        LobbyWindow.notifyTeamChanged();
    }

    /** Ventana al tamaño actual (normal o doble), con los pies en el mismo lugar y dentro de la pantalla. */
    private void applySize() {
        if (stage == null || imageView == null) return;
        double w = CANVAS_WIDTH * size(), h = CANVAS_HEIGHT * size();
        if (imageView.getFitWidth() == w) return;
        double dx = (imageView.getFitWidth() - w) / 2, dy = imageView.getFitHeight() - h;
        imageView.setFitWidth(w);
        imageView.setFitHeight(h);
        stage.setWidth(w);
        stage.setHeight(h);
        if (away || teleporting) { // en la sala o en una pelea: vuelve con el tamaño nuevo al mismo lugar
            lobbyReturnX += dx;
            lobbyReturnY += dy;
            arenaReturnX += dx;
            arenaReturnY += dy;
            return;
        }
        Rectangle2D b = Screen.getPrimary().getVisualBounds();
        stage.setX(Math.max(b.getMinX(), Math.min(stage.getX() + dx, b.getMaxX() - w)));
        stage.setY(Math.max(b.getMinY(), Math.min(stage.getY() + dy, b.getMaxY() - h)));
    }

    /** Especie para el VS Online: la leída del sprite NAME (o escrita), nunca el nombre del archivo. */
    public String speciesForOnline() {
        return recognizedSpecies != null ? recognizedSpecies : "Digimon";
    }

    /**
     * Resultado de una pelea del VS Online. Las Batallas Oficiales (Libre u
     * Original) suman o restan Vital Values y viajan al VB al retirar; la
     * ARENA 2 vs 2 online, como la local, NO cuenta (solo historial y comentario).
     */
    public void recordOnlineBattle(OfficialBattleResult r) {
        VitalRewards.BattleOutcome outcome = switch (r.outcome()) {
            case "WIN" -> VitalRewards.BattleOutcome.WIN;
            case "LOSS" -> VitalRewards.BattleOutcome.LOSS;
            default -> VitalRewards.BattleOutcome.DRAW;
        };
        if (Protocol.MODE_ARENA.equals(r.mode())) {
            BattleHistory.add(displayName + " + compañero", "ARENA 2 vs 2 online", "rival " + LabWindow.stageName(r.rivalStage()),
                    resultName(outcome), "no cuenta");
            if (outcome != VitalRewards.BattleOutcome.DRAW) {
                reactOnly(outcome, registry.secondary().map(DigimonInstance::getDisplayName).orElse(null));
            }
            return;
        }
        recordAndReact(outcome, r.rivalStage(), null,
                "Oficial (" + (Protocol.MODE_ORIGINAL.equals(r.mode()) ? "Original" : "Libre") + ")");
    }

    private Image nativeNameImage(DimVPetData card, int slot) {
        return DimSpriteImageFactory.toNativeImage(card.getSpritesForSlot(slot).get(0));
    }

    private void applyBattleResult(BattleEngine.BattleResult result, int rivalStage, String rivalName) {
        VitalRewards.BattleOutcome outcome = result.draw ? VitalRewards.BattleOutcome.DRAW
                : result.won ? VitalRewards.BattleOutcome.WIN : VitalRewards.BattleOutcome.LOSS;
        recordAndReact(outcome, rivalStage, rivalName, "Aleatoria");
    }

    private static String resultName(VitalRewards.BattleOutcome outcome) {
        return switch (outcome) {
            case WIN -> "Victoria";
            case LOSS -> "Derrota";
            case DRAW -> "Empate";
        };
    }

    /**
     * Pose de victoria (IDLE / VICTORY) o derrota (IDLE / SLEEP) al VOLVER de una pelea, con su
     * sonido: en la pantalla de la pelea ya no suenan (pedido del usuario, 2026-10-05). Sin sonido
     * cuando otro ya lo pone (ARENA: suena una sola vez para todo el equipo). null = empate o sin terminar.
     */
    private void reactToBattle(Boolean won, boolean withSound) {
        if (won == null) return;
        if (won) companion.victoryAction();
        else companion.loseAction();
        if (withSound) Sounds.play(won ? Sfx.VICTORIA : Sfx.DERROTA);
    }

    /**
     * Solo el comentario del Digimon, sin tocar el récord (la ARENA no cuenta). {@code partner} = su
     * compañero de equipo, para el {companero} del banco (null o genérico = sin esas frases).
     */
    private void reactOnly(VitalRewards.BattleOutcome outcome, String partner) {
        Map<String, String> vars = new HashMap<>(playerVars());
        boolean namedPartner = partner != null && !partner.isBlank() && !Set.of("Compañero", "Digimon").contains(partner);
        if (namedPartner) vars.put("companero", partner);
        reactAfterBattle(outcome == VitalRewards.BattleOutcome.WIN ? "arena_victoria" : "arena_derrota", vars);
    }

    /**
     * Suma la batalla al récord (Vital Values por etapa del rival) y el Digimon la comenta con el banco
     * de frases al volver al escritorio. {@code rivalName} = especie del rival si se pudo leer (null =
     * sin las frases con {rival}).
     */
    private void recordAndReact(VitalRewards.BattleOutcome outcome, int rivalStage, String rivalName, String historyMode) {
        int delta = progress.recordBattle(outcome, rivalStage, VitalRewards.load());
        if (capsuleId != null) LabStorage.saveProgress(capsuleId, progress.toProperties());
        BattleHistory.add(displayName, historyMode, "rival " + LabWindow.stageName(rivalStage), resultName(outcome),
                String.format("%+d (estimado)", delta));
        Map<String, String> vars = new HashMap<>(playerVars());
        if (delta != 0) vars.put("vv", String.valueOf(Math.abs(delta)));
        if (rivalName != null && !rivalName.isBlank()) vars.put("rival", rivalName);
        reactAfterBattle(switch (outcome) {
            case WIN -> "victoria";
            case LOSS -> "derrota";
            case DRAW -> "empate";
        }, vars);
    }

    // ---------- Retirar al Digimon (volver al Vital Bracelet) ----------

    /**
     * Pedido del usuario: el Digimon se despide con el saldo de TODAS sus
     * batallas (aleatorias y oficiales), resumido en UN reporte de batalla
     * dentro de una VS DIM nueva hecha desde la original (VsDimWriter), igual
     * al que escribe un VB rival: saldo positivo = victoria contra el rival
     * más fuerte que venció, negativo = derrota contra el más fuerte que lo
     * venció, sin saldo = copia sin reporte. El VB calcula los Vital Values
     * reales al leerlo. Después se va por un portal, se abre la carpeta con
     * el archivo para pasarlo al VB y vuelve la pantalla de inicio.
     */
    private void retireAction() {
        if (retiring) return;
        VitalRewards rewards = VitalRewards.load();
        int before = progress.getImportedVitalValues();
        int after = progress.getVitalValues(rewards);
        int change = after - before;
        Optional<DigimonProgress.BattleReport> report = progress.battleReport();

        String verdict = report.map(r -> (r.won()
                        ? "Saldo POSITIVO: vuelve como VICTORIA contra un rival " : "Saldo NEGATIVO: vuelve como DERROTA contra un rival ")
                        + stageName(r.rivalStage()) + ".")
                .orElse(progress.getBattles() == 0 ? "No peleó: vuelve igual que llegó."
                        : "Saldo en cero: vuelve igual que llegó.");

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(stage);
        confirm.setTitle("Retirar a " + displayName);
        confirm.setHeaderText("¿Retirar a " + displayName + " y devolverlo al Vital Bracelet?");
        confirm.setContentText("Batallas: " + progress.getBattles() + "  (" + progress.getWins() + " ganadas, "
                + progress.getLosses() + " perdidas, " + progress.getDraws() + " empates)\n"
                + "Saldo estimado: " + String.format("%+d", change) + " Vital Values\n\n"
                + verdict + "\n\nEl Vital Bracelet calcula los Vital Values reales al recibirlo. "
                + "Se creará una VS DIM nueva; la original no se toca. Usa una VS DIM recién "
                + "extraída del VB. El Digimon se irá del escritorio.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        Path target = AppPaths.returns().resolve(returnFileName(report));
        try {
            if (report.isPresent()) {
                VsDimWriter.writeBattleReport(sourceBinPath, target, report.get().rivalStage(), report.get().won());
            } else {
                VsDimWriter.copyUnchanged(sourceBinPath, target);
            }
        } catch (IOException e) {
            Alert error = new Alert(Alert.AlertType.ERROR, "No se pudo crear la VS DIM de vuelta:\n" + e.getMessage());
            error.initOwner(stage);
            error.setHeaderText(null);
            error.showAndWait();
            return;
        }
        Sounds.play(Sfx.RESPALDO); // la VS DIM para el VB quedó escrita
        System.out.println("[RETIRO] " + displayName + ": " + report.map(r -> (r.won() ? "victoria" : "derrota")
                + " contra etapa " + r.rivalStage()).orElse("sin reporte") + " en " + target);

        // El saldo ya viaja al VB en el reporte: la cápsula empieza de cero.
        if (capsuleId != null) LabStorage.resetProgress(capsuleId);
        retiring = true;
        companion.setMenuOpen(true);
        stopAll();
        animationPlayer.stop();
        voice.react("retiro", playerVars());
        enterPortal(new TeleportAnimator(), animationPlayer.getSpriteSet(), () -> {
            showInExplorer(target);
            // Primero la pantalla de inicio y DESPUÉS se cierra esta ventana: si no quedara
            // ninguna ventana abierta, JavaFX cerraría el programa.
            if (onRetired != null) onRetired.accept(this);
            dispose();
        });
    }

    /** "VS DIM MagnaKidmon 2026-09-28 18-40-00 (victoria vs Perfect).bin" -- solo caracteres válidos en Windows. */
    private String returnFileName(Optional<DigimonProgress.BattleReport> report) {
        String species = recognizedSpecies != null ? recognizedSpecies : "Digimon";
        species = species.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (species.isEmpty()) species = "Digimon";
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
        String result = report.map(r -> (r.won() ? "victoria" : "derrota") + " vs " + stageName(r.rivalStage()))
                .orElse("sin batalla");
        return "VS DIM " + species + " " + when + " (" + result + ").bin";
    }

    private static String stageName(int dimStage) {
        return switch (dimStage) {
            case 2 -> "Child";
            case 3 -> "Adult";
            case 4 -> "Perfect";
            case 5 -> "Ultimate";
            default -> "Baby";
        };
    }

    /** Abre el Explorador con el archivo seleccionado (Windows); si falla, solo la carpeta. */
    private static void showInExplorer(Path file) {
        try {
            new ProcessBuilder("explorer.exe", "/select,", file.toAbsolutePath().toString()).start();
        } catch (IOException e) {
            try {
                java.awt.Desktop.getDesktop().open(file.getParent().toFile());
            } catch (Exception ignored) {
                System.out.println("[RETIRO] Abre a mano: " + file.getParent());
            }
        }
    }

    // ---------- Sprites ----------

    private DimSpriteSet buildSpriteSetForSlot(DimVPetData card, int slot) {
        int dimStage = card.getStageForSlot(slot);
        VPetStageTier stageTier = stageTierForDimStage(dimStage);
        List<SpriteData.Sprite> sprites = card.getSpritesForSlot(slot);

        DimSpriteSet spriteSet = new DimSpriteSet(stageTier, CANVAS_WIDTH, CANVAS_HEIGHT);

        if (stageTier == VPetStageTier.CHILD_PLUS) {
            spriteSet.put(SpriteRole.NAME,    img(sprites.get(0)));
            spriteSet.put(SpriteRole.IDLE_1,  img(sprites.get(1)));
            spriteSet.put(SpriteRole.IDLE_2,  img(sprites.get(2)));
            spriteSet.put(SpriteRole.WALK_1,  img(sprites.get(3)));
            spriteSet.put(SpriteRole.WALK_2,  img(sprites.get(4)));
            spriteSet.put(SpriteRole.RUN_1,   img(sprites.get(5)));
            spriteSet.put(SpriteRole.RUN_2,   img(sprites.get(6)));
            spriteSet.put(SpriteRole.TRAIN_1, img(sprites.get(7)));
            spriteSet.put(SpriteRole.TRAIN_2, img(sprites.get(8)));
            spriteSet.put(SpriteRole.VICTORY, img(sprites.get(9)));
            spriteSet.put(SpriteRole.SLEEP,   img(sprites.get(10)));
            spriteSet.put(SpriteRole.ATTACK,  img(sprites.get(11)));
            spriteSet.put(SpriteRole.DODGE,   img(sprites.get(12)));
            if (sprites.size() > 13) {
                spriteSet.put(SpriteRole.SPLASH, img(sprites.get(13)));
            }
        } else {
            spriteSet.put(SpriteRole.NAME,    img(sprites.get(0)));
            spriteSet.put(SpriteRole.IDLE_1,  img(sprites.get(1)));
            spriteSet.put(SpriteRole.IDLE_2,  img(sprites.get(2)));
            spriteSet.put(SpriteRole.WALK_1,  img(sprites.get(3)));
            spriteSet.put(SpriteRole.VICTORY, img(sprites.get(4)));
            spriteSet.put(SpriteRole.SLEEP,   img(sprites.get(5)));
            if (stageTier != VPetStageTier.BABY_I && sprites.size() > 6) {
                spriteSet.put(SpriteRole.SPLASH, img(sprites.get(6)));
            }
        }
        return spriteSet;
    }

    private Image img(SpriteData.Sprite sprite) {
        return DimSpriteImageFactory.toImage(sprite, CANVAS_WIDTH, CANVAS_HEIGHT);
    }

    private VPetStageTier stageTierForDimStage(int dimStage) {
        if (dimStage == 0) return VPetStageTier.BABY_I;
        if (dimStage == 1) return VPetStageTier.BABY_II;
        return VPetStageTier.CHILD_PLUS;
    }

    // ---------- Ciclo de vida de la ventana ----------

    public void stopAll() {
        if (companion != null) companion.stop();
        if (thoughtTimer != null) thoughtTimer.stop();
    }

    public void dispose() {
        stopAll();
        if (speechBubble != null) speechBubble.hide();
        if (stage != null) stage.close();
    }

    public String getInstanceId() { return instanceId; }
    public Path getSourceBinPath() { return sourceBinPath; }
    public int getCurrentSlot() { return currentSlot; }
    public VPetStageTier getCurrentStage() { return currentStage; }
    /** Su especie (escrita o leída del sprite NAME); si no, "Digimon" (nunca el nombre del archivo). */
    public String getDisplayName() {
        return recognizedSpecies != null ? recognizedSpecies : "Digimon";
    }
    public DimVPetData getDimData() { return dimData; }
    public DigimonProgress getProgress() { return progress; }
    public DigimonAge getAge() { return age; }
    public double getX() { return stage != null ? stage.getX() : 0; }
    public double getY() { return stage != null ? stage.getY() : 0; }
}
