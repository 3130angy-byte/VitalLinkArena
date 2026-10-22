package org.example.battle;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import org.example.animation.DimSpriteSet;
import org.example.animation.SpriteRole;
import org.example.sound.Sfx;
import org.example.sound.Sounds;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Pantalla del 1 vs 1 (Batalla aleatoria y Batalla Oficial). BattleResult / RoundOutcome siguen
 * siendo la ÚNICA fuente de verdad: aquí solo se reproducen.
 *
 * Rediseño 2026-10-05 (pedido del usuario):
 * <ul>
 * <li>TODO en pixel art en una cuadrícula de 480x270 (16:9) agrandada x2, x3... con vecino más
 * cercano: el coliseo (tools/GenerarColiseo.java), los marcadores, los textos (PixelFont), los
 * ataques del firmware y los Digimon a 1:1 tienen píxeles del mismo tamaño. 16:9 = en el celular va
 * en horizontal.</li>
 * <li>Marcadores sin el rostro recortado (la DIM no trae retrato y el recorte fallaba): insignia de
 * atributo, sprite NAME (se desplaza si no cabe), barra de vida sin número y el atributo escrito.</li>
 * <li>Animación a saltos de 0,1 s como en el Vital Bracelet (antes se deslizaba a 60 fps).</li>
 * <li>READY → GO! antes de la primera ronda.</li>
 * <li>Aviso del ataque grande con sprites propios (no hay splash art): franja de corte con el
 * Digimon x2 y "BIG ATTACK!", luego se carga (parpadeo blanco y chispas) y dispara.</li>
 * </ul>
 */
public class BattlePresentationScreen {

    /** Tamaño de la escena en píxeles de pixel art (se agranda en múltiplos enteros). */
    public static final int SCENE_W = 480, SCENE_H = 270;
    public static final double BG_ASPECT = SCENE_W / (double) SCENE_H;
    /** Un cuadro de la animación: todo cambia a saltos de 0,1 s, como en el Vital Bracelet. */
    private static final double TICK = 0.1;

    private static final int PLAYER_CX = 150, ENEMY_CX = 330, FEET_Y = 226;
    /** Altura del centro del cuerpo sobre los pies (de ahí sale y ahí pega el proyectil). */
    private static final int BODY_Y = FEET_Y - 26;
    private static final int PANEL_W = 176, PANEL_H = 40, PANEL_Y = 4, PANEL_MARGIN = 6;
    private static final int NAME_WINDOW_W = 150, NAME_H = 16;
    private static final int BAR_W = 150, BAR_H = 6;

    private static final int INTRO_TICKS = 10, READY_TICKS = 8, GO_TICKS = 6, ROUND_LABEL_TICKS = 5;
    private static final int POSE_TICKS = 3, TRAVEL_STEPS = 6, MISS_EXTRA_STEPS = 3, IMPACT_TICKS = 3, HP_STEPS = 3;
    private static final int ACTION_GAP_TICKS = 3, ROUND_PAUSE_TICKS = 4, RESULT_HOLD_TICKS = 14;
    private static final int CUTIN_TICKS = 10, CHARGE_TICKS = 4;
    /** Cambio IDLE_1 / IDLE_2 y pausas del nombre que se desplaza (en ticks). */
    private static final int IDLE_SWAP_TICKS = 5, NAME_PAUSE_TICKS = 8, NAME_SCROLL_PX = 2;
    /** Los sprites de ataque del firmware miran a la izquierda. */
    private static final boolean EFFECT_FACES_RIGHT = false;

    private static final Color INK = Color.rgb(7, 11, 31);
    private static final Color PANEL = Color.rgb(12, 20, 51);
    private static final Color PANEL_LINE = Color.rgb(90, 184, 255);
    private static final Color PANEL_SHADE = Color.rgb(24, 39, 95);
    private static final Color WHITE = Color.rgb(255, 255, 255);
    private static final Color YELLOW = Color.rgb(255, 211, 74);
    private static final Color ORANGE = Color.rgb(255, 154, 46);
    private static final Color CREAM = Color.rgb(255, 242, 190);

    private final AttackSpriteResolver attackSpriteResolver;
    private final Pane root = new Pane();
    private Canvas canvas;
    private int scale = 1;
    private Image background, impactImage;
    private Timeline ambient, script;
    private int ambientTick = 0;

    private Fighter player, enemy;
    private Image projectile;
    private boolean projectileMirror, projectileVisible;
    private int projectileX, projectileY;
    private boolean impactVisible;
    private int impactX, impactY, shakeX, shakeY;
    private String roundText = "";
    private String bigText = "";
    private Color bigColor = WHITE;
    private int bigScale = 3;
    /** Franja del ataque grande: quién ataca y en qué cuadro va (-1 = no hay). */
    private Fighter cutIn;
    private int cutInStep = -1;
    /** Carga antes de disparar el ataque grande: chispas que se juntan (-1 = no hay). */
    private Fighter charging;
    private int chargeStep = -1;
    private final Map<Image, Image> silhouettes = new IdentityHashMap<>();

    public BattlePresentationScreen(AttackSpriteResolver attackSpriteResolver) {
        this.attackSpriteResolver = attackSpriteResolver;
    }

    /** Escala entera más grande con la que la escena entra en ese espacio (mínimo 1). */
    public static int scaleFor(double width, double height) {
        return Math.max(1, (int) Math.floor(Math.min(width / SCENE_W, height / SCENE_H)));
    }

    /**
     * playerPortrait/enemyPortrait = IDLE_1. playerNameImage/enemyNameImage = sprite NAME en tamaño
     * NATIVO. HP máximo y atributo salen del Combatant real. La escena se agranda a la escala entera
     * que entra en sceneW x sceneH y queda centrada (lo que sobra, oscuro).
     */
    public Node buildNode(Image playerPortrait, Image enemyPortrait,
                          Image playerNameImage, Image enemyNameImage,
                          BattleEngine.Combatant player, BattleEngine.Combatant enemy,
                          double sceneW, double sceneH) {
        this.player = new Fighter(true, PLAYER_CX, playerPortrait, playerNameImage, player);
        this.enemy = new Fighter(false, ENEMY_CX, enemyPortrait, enemyNameImage, enemy);
        background = load("/battle/coliseo.png");
        impactImage = load("/attacks/impact.png");

        scale = scaleFor(sceneW, sceneH);
        canvas = new Canvas(SCENE_W * scale, SCENE_H * scale);
        canvas.setLayoutX(Math.floor((sceneW - canvas.getWidth()) / 2));
        canvas.setLayoutY(Math.floor((sceneH - canvas.getHeight()) / 2));
        root.setPrefSize(sceneW, sceneH);
        root.setMinSize(sceneW, sceneH);
        root.setBackground(new Background(new BackgroundFill(INK, null, null)));
        root.getChildren().setAll(canvas);

        // Reloj de ambiente: respiración IDLE_1 / IDLE_2, nombres que se desplazan y la franja del ataque grande.
        ambient = new Timeline(new KeyFrame(Duration.seconds(TICK), e -> {
            ambientTick++;
            this.player.scrollName();
            this.enemy.scrollName();
            render();
        }));
        ambient.setCycleCount(Animation.INDEFINITE);
        ambient.play();
        render();
        return root;
    }

    /**
     * Premio del campeón de un torneo (pedido del usuario, 2026-10-18): el Digimon que lo tiene encendido se ve al
     * DOBLE, con cada píxel en 2x2. Llamar después de buildNode. La Aventura no lo usa (queda igual).
     */
    public void setBig(boolean playerBig, boolean enemyBig) {
        if (player != null) player.big = playerBig;
        if (enemy != null) enemy.big = enemyBig;
        render();
    }

    /** Texto final para quien MIRA la pelea (pantalla de combates en vivo, v13): en vez de ¡VICTORIA!/DERROTA... */
    private String endText;

    public void setEndText(String text) {
        this.endText = text;
    }

    public void play(BattleEngine.BattleResult result, DimSpriteSet playerSprites, DimSpriteSet enemySprites,
                     BattleEngine.Combatant playerCombatant, BattleEngine.Combatant enemyCombatant,
                     Runnable onComplete) {
        player.sprites = playerSprites;
        enemy.sprites = enemySprites;
        script = new Timeline();
        Sounds.play(Sfx.INICIO_BATALLA);

        // READY -> GO! (como en el Vital Bracelet antes de pelear).
        int t = INTRO_TICKS;
        at(t, () -> showBig("READY", WHITE, 3, Sfx.LISTO));
        t += READY_TICKS;
        at(t, () -> showBig("GO!", YELLOW, 4, Sfx.GO));
        t += GO_TICKS;
        at(t, () -> bigText = "");

        for (BattleEngine.RoundOutcome round : result.rounds) {
            t = appendRound(t, round, playerCombatant, enemyCombatant);
        }

        // Solo el texto del resultado: la pose y el sonido de victoria o derrota llegan cuando el
        // Digimon vuelve al escritorio o a la sala (pedido del usuario, 2026-10-05).
        at(t, () -> {
            if (endText != null) showBig(endText, YELLOW, endText.length() > 13 ? 2 : 3, null);
            else if (result.draw) showBig("EMPATE", YELLOW, 3, null);
            else if (result.won) showBig("¡VICTORIA!", Color.rgb(94, 221, 138), 3, null);
            else showBig("DERROTA...", Color.rgb(255, 106, 90), 3, null);
        });
        t += RESULT_HOLD_TICKS;
        script.getKeyFrames().add(new KeyFrame(seconds(t), e -> {
            if (onComplete != null) onComplete.run();
        }));
        script.play();
    }

    /** Detiene los relojes de la pantalla. Llamar al terminar la batalla. */
    public void dispose() {
        if (ambient != null) ambient.stop();
        if (script != null) script.stop();
    }

    // ---------------------------------------------------------------- guion de la pelea

    private int appendRound(int t, BattleEngine.RoundOutcome round,
                            BattleEngine.Combatant playerCombatant, BattleEngine.Combatant enemyCombatant) {
        at(t, () -> {
            player.pose = null;
            enemy.pose = null;
            roundText = "RONDA " + round.roundNumber;
        });
        t += ROUND_LABEL_TICKS;

        // Juega primero PLAYER y después ENEMY; quien pierde la ronda usa SIEMPRE su ataque pequeño
        // y falla (el motor solo evalúa el grande para quien gana la ronda).
        boolean playerHits = round.attacker == BattleEngine.Side.PLAYER;
        boolean playerBig = playerHits && round.attackType == BattleEngine.AttackType.BIG;
        t = appendAction(t, player, enemy, playerHits, playerBig,
                playerHits ? round.attackId : playerCombatant.smallAttackId, round.playerHpAfter, round.enemyHpAfter);
        if (playerHits && (round.playerHpAfter <= 0 || round.enemyHpAfter <= 0)) return t;

        t += ACTION_GAP_TICKS;
        boolean enemyHits = round.attacker == BattleEngine.Side.ENEMY;
        boolean enemyBig = enemyHits && round.attackType == BattleEngine.AttackType.BIG;
        t = appendAction(t, enemy, player, enemyHits, enemyBig,
                enemyHits ? round.attackId : enemyCombatant.smallAttackId, round.playerHpAfter, round.enemyHpAfter);
        return t + ROUND_PAUSE_TICKS;
    }

    private int appendAction(int t, Fighter attacker, Fighter defender, boolean hit, boolean big, int attackId,
                             int playerHpAfter, int enemyHpAfter) {
        Image effect = big ? attackSpriteResolver.getBigAttack(attackId) : attackSpriteResolver.getSmallAttack(attackId);

        if (big) t = appendBigAttackWarning(t, attacker);

        at(t, () -> attacker.pose = SpriteRole.ATTACK);
        t += POSE_TICKS;

        int dir = attacker.left ? 1 : -1;
        int startX = attacker.cx + dir * 22, endX = defender.cx;
        int stepX = (endX - startX) / TRAVEL_STEPS;
        at(t, () -> {
            Sounds.play(big ? Sfx.ATAQUE_GRANDE : Sfx.ATAQUE);
            projectile = effect;
            projectileMirror = EFFECT_FACES_RIGHT != attacker.left;
            projectileX = startX;
            projectileY = bodyY(attacker);
            projectileVisible = effect != null;
        });
        for (int i = 1; i <= TRAVEL_STEPS; i++) {
            int x = startX + stepX * i;
            at(t + i, () -> projectileX = x);
        }
        t += TRAVEL_STEPS;

        if (hit) {
            at(t, () -> {
                projectileVisible = false;
                impactVisible = true;
                impactX = defender.cx;
                impactY = bodyY(defender);
                defender.hidden = true;
                Sounds.play(Sfx.GOLPE);
            });
            // Parpadea al recibir el golpe; la vida baja a saltos; el grande además sacude la arena.
            for (int i = 1; i <= 3; i++) {
                boolean hidden = i % 2 == 0;
                at(t + i, () -> defender.hidden = hidden);
            }
            at(t + 4, () -> defender.hidden = false);
            at(t + IMPACT_TICKS, () -> impactVisible = false);
            double fromPlayer = player.hpShown, fromEnemy = enemy.hpShown;
            double toPlayer = Math.max(0, playerHpAfter) / (double) player.maxHp;
            double toEnemy = Math.max(0, enemyHpAfter) / (double) enemy.maxHp;
            for (int i = 1; i <= HP_STEPS; i++) {
                double f = i / (double) HP_STEPS;
                at(t + i, () -> {
                    player.hpShown = fromPlayer + (toPlayer - fromPlayer) * f;
                    enemy.hpShown = fromEnemy + (toEnemy - fromEnemy) * f;
                });
            }
            if (big) {
                int[][] shake = {{3, 0}, {-3, 1}, {2, -1}, {-1, 0}, {0, 0}};
                for (int i = 0; i < shake.length; i++) {
                    int[] s = shake[i];
                    at(t + i, () -> { shakeX = s[0]; shakeY = s[1]; });
                }
            }
            t += 5;
        } else {
            // Falla: el proyectil sigue de largo y se apaga parpadeando; el otro salta para esquivar.
            for (int i = 1; i <= MISS_EXTRA_STEPS; i++) {
                int x = endX + stepX * i;
                boolean visible = i % 2 == 1;
                at(t + i, () -> { projectileX = x; projectileVisible = visible && projectile != null; });
            }
            at(t + MISS_EXTRA_STEPS + 1, () -> projectileVisible = false);
            int away = -dir;
            int[][] hop = {{3, -6}, {6, -10}, {6, -6}, {3, -2}, {0, 0}};
            int hopStart = t - 2;
            at(hopStart, () -> {
                defender.pose = defender.sprites.has(SpriteRole.DODGE) ? SpriteRole.DODGE : SpriteRole.IDLE_1;
                Sounds.play(Sfx.ESQUIVA);
            });
            for (int i = 0; i < hop.length; i++) {
                int[] h = hop[i];
                at(hopStart + i, () -> { defender.dx = h[0] * away; defender.dy = h[1]; });
            }
            t = Math.max(t + MISS_EXTRA_STEPS + 1, hopStart + hop.length);
        }
        at(t, () -> {
            attacker.pose = null;
            defender.pose = null;
        });
        return t;
    }

    /**
     * Aviso del ataque grande (concepto propio, con los sprites que sí tenemos): la arena se oscurece y
     * cruza una franja con el Digimon x2 en pose de ataque, líneas de velocidad y "BIG ATTACK!"; después,
     * de vuelta en la arena, se carga: parpadea en blanco y unas chispas se juntan en él.
     */
    private int appendBigAttackWarning(int t, Fighter attacker) {
        at(t, () -> {
            cutIn = attacker;
            cutInStep = 0;
            Sounds.play(Sfx.CUTIN_GRANDE);
        });
        for (int i = 1; i < CUTIN_TICKS; i++) {
            int step = i;
            at(t + i, () -> cutInStep = step);
        }
        t += CUTIN_TICKS;
        at(t, () -> {
            cutIn = null;
            cutInStep = -1;
            attacker.pose = SpriteRole.ATTACK;
            charging = attacker;
            chargeStep = 0;
        });
        for (int i = 0; i < CHARGE_TICKS; i++) {
            int step = i;
            at(t + i, () -> {
                chargeStep = step;
                attacker.silhouette = step % 2 == 0;
            });
        }
        t += CHARGE_TICKS;
        at(t, () -> {
            charging = null;
            chargeStep = -1;
            attacker.silhouette = false;
        });
        return t;
    }

    private void showBig(String text, Color color, int textScale, Sfx sound) {
        bigText = text;
        bigColor = color;
        bigScale = textScale;
        if (sound != null) Sounds.play(sound);
    }

    private void at(int tick, Runnable action) {
        script.getKeyFrames().add(new KeyFrame(seconds(tick), e -> {
            action.run();
            render();
        }));
    }

    private static Duration seconds(int tick) {
        return Duration.seconds(tick * TICK);
    }

    // ---------------------------------------------------------------- dibujo

    private void render() {
        if (canvas == null) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setImageSmoothing(false);
        g.save();
        g.scale(scale, scale);
        g.setFill(INK);
        g.fillRect(0, 0, SCENE_W, SCENE_H);

        // Arena (se sacude con el golpe grande).
        g.save();
        g.translate(shakeX, shakeY);
        if (background != null) g.drawImage(background, 0, 0);
        for (Fighter f : new Fighter[]{player, enemy}) drawShadow(g, f);
        for (Fighter f : new Fighter[]{player, enemy}) drawFighter(g, f);
        if (projectileVisible && projectile != null) {
            drawImage(g, projectile, projectileX - (int) projectile.getWidth() / 2,
                    projectileY - (int) projectile.getHeight() / 2, projectileMirror);
        }
        if (impactVisible && impactImage != null) {
            g.drawImage(impactImage, impactX - (int) impactImage.getWidth() / 2, impactY - (int) impactImage.getHeight() / 2);
        }
        if (charging != null) drawCharge(g, charging, chargeStep);
        g.restore();

        // Marcadores y ronda (fijos).
        drawPanel(g, player, PANEL_MARGIN);
        drawPanel(g, enemy, SCENE_W - PANEL_MARGIN - PANEL_W);
        if (!roundText.isEmpty()) PixelFont.drawCentered(g, roundText, SCENE_W / 2, 10, 1, WHITE, INK);

        if (cutIn != null) drawCutIn(g, cutIn, cutInStep);
        if (!bigText.isEmpty()) PixelFont.drawCentered(g, bigText, SCENE_W / 2, 104, bigScale, bigColor, INK);
        g.restore();
    }

    private void drawShadow(GraphicsContext g, Fighter f) {
        g.setFill(Color.rgb(0, 0, 0, 0.35));
        int[] half = {8, 13, 15, 13, 8};
        for (int i = 0; i < half.length; i++) {
            int k = f.big ? 2 : 1;
            g.fillRect(f.cx + f.dx - half[i] * k, FEET_Y - 2 + i, half[i] * 2 * k, 1);
        }
    }

    private void drawFighter(GraphicsContext g, Fighter f) {
        if (f.hidden) return;
        Image img = f.currentImage(ambientTick / IDLE_SWAP_TICKS % 2 == 1);
        if (img == null) return;
        if (f.silhouette) img = silhouette(img);
        int k = f.big ? 2 : 1; // premio: cada píxel 2x2 (el lienzo ya no suaviza)
        int w = (int) img.getWidth() * k, h = (int) img.getHeight() * k;
        // Convención del proyecto: los sprites miran a la izquierda; mirar a la derecha = espejado.
        drawImage(g, img, f.cx + f.dx - w / 2, FEET_Y + f.dy - h, w, h, f.left);
    }

    /** Igual, a un tamaño entero dado (el premio x2; el lienzo dibuja sin suavizado = píxeles duplicados). */
    private static void drawImage(GraphicsContext g, Image img, int x, int y, int w, int h, boolean mirror) {
        if (!mirror) {
            g.drawImage(img, x, y, w, h);
            return;
        }
        g.save();
        g.translate(x + w, y);
        g.scale(-1, 1);
        g.drawImage(img, 0, 0, w, h);
        g.restore();
    }

    /** Centro del cuerpo: de ahí sale y ahí pega el proyectil (más alto si se luce al doble). */
    private static int bodyY(Fighter f) {
        return f.big ? FEET_Y - 52 : BODY_Y;
    }

    private static void drawImage(GraphicsContext g, Image img, int x, int y, boolean mirror) {
        if (!mirror) {
            g.drawImage(img, x, y);
            return;
        }
        g.save();
        g.translate(x + img.getWidth(), y);
        g.scale(-1, 1);
        g.drawImage(img, 0, 0);
        g.restore();
    }

    /** Marcador: insignia de atributo, sprite NAME, barra de vida (sin número) y el atributo escrito. */
    private void drawPanel(GraphicsContext g, Fighter f, int x) {
        int y = PANEL_Y, w = PANEL_W, h = PANEL_H;
        g.setFill(PANEL);
        g.fillRect(x + 1, y + 1, w - 2, h - 2);
        g.setFill(PANEL_LINE);
        g.fillRect(x + 2, y, w - 4, 1);
        g.fillRect(x + 2, y + h - 1, w - 4, 1);
        g.fillRect(x, y + 2, 1, h - 4);
        g.fillRect(x + w - 1, y + 2, 1, h - 4);
        g.fillRect(x + 1, y + 1, 1, 1);
        g.fillRect(x + w - 2, y + 1, 1, 1);
        g.fillRect(x + 1, y + h - 2, 1, 1);
        g.fillRect(x + w - 2, y + h - 2, 1, 1);
        g.setFill(PANEL_SHADE);
        g.fillRect(x + 2, y + 1, w - 4, 1);

        int badgeX = f.left ? x + 4 : x + w - 17;
        drawBadge(g, f.attribute, badgeX, y + 4);

        int contentX = f.left ? x + 21 : x + w - 21 - NAME_WINDOW_W;
        if (f.nameImage != null && f.nameWidth > 0) {
            int nameW = f.nameWidth;
            int nameX = nameW <= NAME_WINDOW_W
                    ? (f.left ? contentX : contentX + NAME_WINDOW_W - nameW)
                    : contentX - f.nameOffset;
            g.save();
            g.beginPath();
            g.rect(contentX, y + 3, NAME_WINDOW_W, NAME_H);
            g.clip();
            double nameH = f.nameImage.getHeight();
            g.drawImage(f.nameImage, f.nameLeft, 0, nameW, nameH, nameX, y + 3, nameW, nameH);
            g.restore();
        }

        int barY = y + 22;
        g.setFill(INK);
        g.fillRect(contentX - 1, barY - 1, BAR_W + 2, BAR_H + 2);
        g.setFill(PANEL_SHADE);
        g.fillRect(contentX, barY, BAR_W, BAR_H);
        int fill = (int) Math.round(BAR_W * Math.max(0, Math.min(1, f.hpShown)));
        if (fill > 0) {
            Color[] c = hpColors(f.hpShown);
            int fx = f.left ? contentX : contentX + BAR_W - fill;
            g.setFill(c[1]);
            g.fillRect(fx, barY, fill, BAR_H);
            g.setFill(c[0]);
            g.fillRect(fx, barY + 1, fill, 1);
            g.setFill(c[2]);
            g.fillRect(fx, barY + BAR_H - 1, fill, 1);
        }

        String attr = attributeName(f.attribute);
        int textX = f.left ? contentX : contentX + BAR_W - PixelFont.smallWidth(attr);
        PixelFont.drawSmall(g, attr, textX, barY + BAR_H + 3, attributeColor(f.attribute).brighter());
    }

    private static Color[] hpColors(double fraction) {
        if (fraction > 0.5) return new Color[]{Color.rgb(205, 247, 218), Color.rgb(94, 221, 138), Color.rgb(39, 174, 96)};
        if (fraction > 0.25) return new Color[]{Color.rgb(255, 242, 190), Color.rgb(255, 211, 74), Color.rgb(212, 161, 15)};
        return new Color[]{Color.rgb(255, 214, 205), Color.rgb(255, 106, 90), Color.rgb(196, 48, 48)};
    }

    /** Insignia redonda de 13x13 con las dos letras del atributo (colores de las insignias de la ARENA). */
    private static void drawBadge(GraphicsContext g, int attribute, int x, int y) {
        Color base = attributeColor(attribute);
        for (int dy = 0; dy < 13; dy++) {
            for (int dx = 0; dx < 13; dx++) {
                double d = Math.hypot(dx - 6, dy - 6);
                if (d > 6.4) continue;
                g.setFill(d > 5.3 ? INK : (dx + dy < 9 && d > 3.5 ? base.brighter() : base));
                g.fillRect(x + dx, y + dy, 1, 1);
            }
        }
        PixelFont.drawSmall(g, attributeAbbrev(attribute), x + 3, y + 4, WHITE);
    }

    /** Franja del ataque grande sobre la arena oscurecida. */
    private void drawCutIn(GraphicsContext g, Fighter f, int step) {
        int top = 82, height = 116;
        g.setFill(Color.rgb(0, 0, 0, 0.6));
        g.fillRect(0, 0, SCENE_W, SCENE_H);
        g.setFill(INK);
        g.fillRect(0, top, SCENE_W, height);
        // Líneas de velocidad: corren hacia atrás del ataque, a saltos.
        int dir = f.left ? 1 : -1;
        Color[] lines = {YELLOW, ORANGE, CREAM, YELLOW, ORANGE};
        for (int i = 0; i < 18; i++) {
            int len = 24 + (i * 37) % 64;
            int ly = top + 6 + (i * 53) % (height - 12);
            int base = (i * 97) % (SCENE_W + 120);
            int lx = Math.floorMod(base - dir * step * 38, SCENE_W + 120) - 60;
            g.setFill(lines[i % lines.length]);
            g.fillRect(lx, ly, len, 1);
        }
        g.setFill(YELLOW);
        g.fillRect(0, top, SCENE_W, 2);
        g.fillRect(0, top + height - 2, SCENE_W, 2);

        // El Digimon x2 entra deslizándose desde su lado en 3 saltos y se queda.
        Image img = f.sprites.has(SpriteRole.ATTACK) ? f.sprites.get(SpriteRole.ATTACK) : f.sprites.get(SpriteRole.IDLE_1);
        int w = (int) img.getWidth() * 2, h = (int) img.getHeight() * 2;
        int finalCx = f.left ? 140 : SCENE_W - 140;
        int startCx = f.left ? -w : SCENE_W + w;
        int cx = step >= 3 ? finalCx : startCx + (finalCx - startCx) * step / 3;
        int x = cx - w / 2, y = top + height - 4 - h;
        if (f.left) {
            g.save();
            g.translate(x + w, y);
            g.scale(-1, 1);
            g.drawImage(img, 0, 0, w, h);
            g.restore();
        } else {
            g.drawImage(img, x, y, w, h);
        }
        if (step >= 2) {
            String text = "BIG ATTACK!";
            int textCx = f.left ? SCENE_W - 140 : 140;
            PixelFont.drawCentered(g, text, textCx, top + height / 2 - 7, 2, step % 2 == 0 ? YELLOW : CREAM, INK);
        }
    }

    /** Chispas que se juntan en el Digimon que carga el ataque grande. */
    private void drawCharge(GraphicsContext g, Fighter f, int step) {
        int r = (30 - step * 8) * (f.big ? 2 : 1);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + step * 0.3;
            int x = f.cx + (int) Math.round(Math.cos(a) * r), y = bodyY(f) + (int) Math.round(Math.sin(a) * r * 0.8);
            g.setFill(i % 2 == 0 ? WHITE : YELLOW);
            g.fillRect(x, y, 2, 2);
        }
    }

    /** El sprite en blanco (mismo contorno): el parpadeo de la carga. */
    private Image silhouette(Image img) {
        return silhouettes.computeIfAbsent(img, source -> {
            int w = (int) source.getWidth(), h = (int) source.getHeight();
            WritableImage out = new WritableImage(w, h);
            PixelReader r = source.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = r.getArgb(x, y) >>> 24;
                    out.getPixelWriter().setArgb(x, y, a == 0 ? 0 : 0xFFFFFFFF);
                }
            }
            return out;
        });
    }

    // ---------------------------------------------------------------- atributo (DIM: 0=Null, 1=Virus, 2=Data, 3=Vaccine, 4=Free)

    static String attributeName(int attribute) {
        return switch (attribute) {
            case 1 -> "VIRUS";
            case 2 -> "DATA";
            case 3 -> "VACCINE";
            case 4 -> "FREE";
            default -> "NULL";
        };
    }

    private static String attributeAbbrev(int attribute) {
        return switch (attribute) {
            case 1 -> "VI";
            case 2 -> "DA";
            case 3 -> "VA";
            case 4 -> "FR";
            default -> "--";
        };
    }

    /** Los mismos colores que las insignias de la ARENA (antes el 1 vs 1 tenía otros: Data era azul). */
    private static Color attributeColor(int attribute) {
        return switch (attribute) {
            case 1 -> Color.web("#8e44c9");
            case 2 -> Color.web("#f08a24");
            case 3 -> Color.web("#27ae60");
            case 4 -> Color.web("#1fb5b0");
            default -> Color.web("#7f8c8d");
        };
    }

    private Image load(String resourcePath) {
        var stream = getClass().getResourceAsStream(resourcePath);
        if (stream == null) {
            System.out.println("BattlePresentationScreen: no se encontró " + resourcePath);
            return null;
        }
        return new Image(stream);
    }

    /** Primera columna con algún píxel visible y cuántas columnas ocupa lo visible ({0, 0} si no hay). */
    private static int[] visibleColumns(Image img) {
        if (img == null) return new int[]{0, 0};
        PixelReader r = img.getPixelReader();
        int w = (int) img.getWidth(), h = (int) img.getHeight(), first = -1, last = -1;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if ((r.getArgb(x, y) >>> 24) > 0) {
                    if (first < 0) first = x;
                    last = x;
                    break;
                }
            }
        }
        return first < 0 ? new int[]{0, 0} : new int[]{first, last - first + 1};
    }

    // ---------------------------------------------------------------- un luchador

    private static final class Fighter {
        final boolean left;
        final int cx;
        final Image portrait, nameImage;
        /** Parte visible del sprite NAME (trae espacio vacío a la derecha: no se desplaza por eso). */
        final int nameLeft, nameWidth;
        final int attribute, maxHp;
        DimSpriteSet sprites;
        /** null = respirando (IDLE_1 / IDLE_2). */
        SpriteRole pose;
        int dx, dy;
        boolean hidden, silhouette;
        /** Premio del campeón de un torneo (2026-10-18): se dibuja al doble, cada píxel 2x2 (pixel art, sin suavizado). */
        boolean big;
        double hpShown = 1;
        int nameOffset, namePause = NAME_PAUSE_TICKS;

        Fighter(boolean left, int cx, Image portrait, Image nameImage, BattleEngine.Combatant c) {
            this.left = left;
            this.cx = cx;
            this.portrait = portrait;
            this.nameImage = nameImage;
            int[] visible = visibleColumns(nameImage);
            this.nameLeft = visible[0];
            this.nameWidth = visible[1];
            this.attribute = c.attribute;
            this.maxHp = Math.max(1, c.hp);
        }

        Image currentImage(boolean secondIdle) {
            if (sprites == null) return portrait;
            if (pose != null && sprites.has(pose)) return sprites.get(pose);
            return secondIdle && sprites.has(SpriteRole.IDLE_2) ? sprites.get(SpriteRole.IDLE_2) : sprites.get(SpriteRole.IDLE_1);
        }

        /** Nombre más largo que su ventana: pausa, avanza de a 2 píxeles, pausa al final y vuelve. */
        void scrollName() {
            int max = nameWidth - NAME_WINDOW_W;
            if (max <= 0) return;
            if (namePause > 0) {
                namePause--;
                return;
            }
            if (nameOffset >= max) {
                nameOffset = 0;
                namePause = NAME_PAUSE_TICKS;
                return;
            }
            nameOffset = Math.min(max, nameOffset + NAME_SCROLL_PX);
            if (nameOffset >= max) namePause = NAME_PAUSE_TICKS;
        }
    }
}
