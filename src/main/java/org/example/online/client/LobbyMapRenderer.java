package org.example.online.client;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import org.example.animation.PortalSpriteSheet;
import org.example.online.LobbyMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Dibujo del 1er piso (LobbyMap) en PIXEL ART real: todo se pinta con
 * rectángulos enteros a 1 píxel por unidad del mundo y luego se agranda con
 * vecino más cercano (sin suavizado), igual que el avatar y los Digimon de
 * la sala. Solo los textos (etiquetas) se dibujan después, nítidos.
 *
 * Paleta del boceto del usuario (baldosas celestes, muros azules con borde
 * cian) + detalles de escena: alfombra roja de torneo, estandartes,
 * marcador "VS ONLINE", vitrinas de trofeos, macetas, bancas, Training Room
 * abierto con piso de madera y aparatos, y un portal decorativo animado.
 * Nada de esto tiene funciones (decisión del usuario): es solo escena.
 */
public final class LobbyMapRenderer {

    private static final int T = LobbyMap.TILE;

    // Vacío fuera del edificio
    private static final Color VOID = Color.rgb(10, 14, 24);
    private static final Color VOID_DOT = Color.rgb(26, 36, 56);
    // Piso del salón
    private static final Color FLOOR_A = Color.rgb(190, 216, 238);
    private static final Color FLOOR_B = Color.rgb(180, 208, 233);
    private static final Color GROUT = Color.rgb(156, 188, 220);
    private static final Color SHINE = Color.rgb(222, 238, 250);
    private static final Color STAFF_FLOOR = Color.rgb(146, 166, 198);
    private static final Color WALL_SHADOW = Color.rgb(20, 40, 80, 0.18);
    // Alfombra
    private static final Color CARPET = Color.rgb(168, 40, 52);
    private static final Color CARPET_DARK = Color.rgb(120, 24, 36);
    private static final Color GOLD = Color.rgb(236, 186, 70);
    private static final Color GOLD_DARK = Color.rgb(180, 132, 40);
    // Muros
    private static final Color WALL_TOP = Color.rgb(24, 48, 98);
    private static final Color WALL_EDGE = Color.rgb(90, 200, 250);
    private static final Color FACE = Color.rgb(40, 84, 150);
    private static final Color FACE_LIGHT = Color.rgb(66, 126, 198);
    private static final Color FACE_SEAM = Color.rgb(30, 64, 120);
    private static final Color BASEBOARD = Color.rgb(16, 30, 64);
    // Training Room
    private static final Color WOOD = Color.rgb(198, 146, 96);
    private static final Color WOOD_LINE = Color.rgb(166, 116, 70);
    private static final Color WOOD_SHINE = Color.rgb(218, 170, 120);
    private static final Color MAT = Color.rgb(54, 104, 180);
    private static final Color MAT_LINE = Color.rgb(232, 238, 246);
    // Otros
    private static final Color SHADOW = Color.rgb(0, 0, 0, 0.22);
    private static final Color SCREEN = Color.rgb(90, 220, 255);
    private static final Color DARK_METAL = Color.rgb(40, 44, 58);
    private static final Color METAL = Color.rgb(120, 128, 148);
    private static final Color LABEL = Color.WHITE;

    /** Centro del portal decorativo (casillas 46-48 x 13-17) y su tamaño en el mundo (sprite a la mitad = 1:1 con la cámara x2). */
    private static final double PORTAL_CX = 47 * T + T / 2.0, PORTAL_CY = 15 * T - 4;
    private static final double PORTAL_W = PortalSpriteSheet.FRAME_WIDTH / 2.0, PORTAL_H = PortalSpriteSheet.FRAME_HEIGHT / 2.0;
    private static final long PORTAL_LOOP_NANOS = 120_000_000L;

    private static PortalSpriteSheet portalSheet;

    private LobbyMapRenderer() {}

    /** Hoja del portal (la misma del escritorio), compartida con LobbyPortalSwap. */
    static PortalSpriteSheet portalSheet() {
        if (portalSheet == null) {
            portalSheet = new PortalSpriteSheet(new Image(LobbyMapRenderer.class.getResourceAsStream("/PortalSpriteV3.png")));
        }
        return portalSheet;
    }

    /**
     * Todo lo que no se mueve, a una imagen de {@code scale} píxeles por
     * unidad del mundo (la sala usa 2 = su cámara). El pixel art se pinta a
     * 1x y se agranda sin suavizado; las etiquetas van encima, nítidas.
     */
    public static Image renderStatic(double scale) {
        int w = LobbyMap.WIDTH, h = LobbyMap.HEIGHT;
        Canvas base = new Canvas(w, h);
        GraphicsContext g = base.getGraphicsContext2D();
        g.setImageSmoothing(false);

        g.setFill(VOID);
        g.fillRect(0, 0, w, h);
        for (int y = 8; y < h; y += 32) for (int x = 8; x < w; x += 32) r(g, x, y, 1, 1, VOID_DOT);

        for (int row = 0; row < LobbyMap.ROWS; row++) {
            for (int col = 0; col < LobbyMap.COLS; col++) {
                drawTile(g, col, row);
            }
        }
        drawTrainingMats(g);
        leaderboard(g, 17 * T, 12 * T);
        pc(g, 34 * T, 12 * T);
        drawScoreboard(g);
        drawPortalBase(g);
        drawEntranceDoors(g);
        for (LobbyMap.Prop p : LobbyMap.PROPS) drawProp(g, p);
        npc(g, 21 * T, 4 * T, Color.rgb(196, 48, 56));   // Batalla oficial
        npc(g, 31 * T, 4 * T, Color.rgb(44, 96, 190));   // Recepción de torneos

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(VOID);
        Image pixels = base.snapshot(params, null);

        int s = Math.max(1, (int) Math.round(scale));
        Canvas big = new Canvas(w * s, h * s);
        GraphicsContext gb = big.getGraphicsContext2D();
        gb.setImageSmoothing(false);
        gb.drawImage(pixels, 0, 0, w * s, h * s);
        gb.scale(s, s);
        for (LobbyMap.Feature f : LobbyMap.FEATURES) label(gb, f.label(), f.x(), f.y() - (f.y() < 6 * T ? 9 : 14));
        label(gb, "Training Room", 6 * T, 5 * T - 6);
        label(gb, "Entrada", 26 * T + T / 2.0, 27 * T - 4);
        return big.snapshot(params, null);
    }

    /**
     * El portal decorativo, animado (se llama en cada cuadro con la cámara ya
     * aplicada). No lleva a ningún lado: es solo escena.
     */
    public static void drawPortalAnimation(GraphicsContext g, long now) {
        Image frame = portalSheet().getLoopFrame((int) ((now / PORTAL_LOOP_NANOS) % PortalSpriteSheet.LOOP_FRAMES));
        g.drawImage(frame, PORTAL_CX - PORTAL_W / 2, PORTAL_CY - PORTAL_H / 2, PORTAL_W, PORTAL_H);
    }

    // ------------------------------------------------------------------ casillas

    private static void drawTile(GraphicsContext g, int col, int row) {
        int x = col * T, y = row * T;
        switch (LobbyMap.at(col, row)) {
            case LobbyMap.FLOOR, LobbyMap.ENTRANCE -> hallFloor(g, col, row);
            case LobbyMap.STAFF_FLOOR, LobbyMap.NPC_RANKING, LobbyMap.NPC_RECEPTION -> {
                plainTile(g, x, y, STAFF_FLOOR, Color.rgb(128, 148, 182));
            }
            case LobbyMap.TRAINING_FLOOR -> woodFloor(g, col, row);
            case LobbyMap.PROP -> {
                if (inTraining(col, row)) woodFloor(g, col, row);
                else hallFloor(g, col, row);
            }
            case LobbyMap.COUNTER -> counter(g, col, row);
            case LobbyMap.TERMINAL, LobbyMap.PC -> wallFace(g, col, row); // el aparato va encima (2 casillas)
            case LobbyMap.PORTAL -> metalFloor(g, x, y);
            default -> wall(g, col, row);
        }
        if (row == 28 && LobbyMap.at(col, row) == LobbyMap.ENTRANCE) entranceMat(g, col, x, y);
    }

    private static boolean inTraining(int col, int row) {
        return (col >= 3 && col <= 8 && row >= 6 && row <= 24) || (col >= 1 && col <= 9 && row >= 11 && row <= 19);
    }

    private static boolean isCarpet(int col, int row) {
        return (row >= 14 && row <= 16 && col >= 11 && col <= 44)
                || (col >= 25 && col <= 27 && row >= 6 && row <= 13)
                || (col >= 25 && col <= 27 && row >= 17 && row <= 27);
    }

    private static boolean inside(int col, int row) {
        return LobbyMap.at(col, row) != LobbyMap.WALL;
    }

    private static void plainTile(GraphicsContext g, int x, int y, Color fill, Color grout) {
        r(g, x, y, T, T, fill);
        r(g, x + T - 1, y, 1, T, grout);
        r(g, x, y + T - 1, T, 1, grout);
    }

    private static void hallFloor(GraphicsContext g, int col, int row) {
        int x = col * T, y = row * T;
        if (isCarpet(col, row) && LobbyMap.at(col, row) != LobbyMap.PROP) {
            carpet(g, col, row);
        } else {
            plainTile(g, x, y, ((col + row) & 1) == 0 ? FLOOR_A : FLOOR_B, GROUT);
            r(g, x + 1, y + 1, 3, 1, SHINE);
            r(g, x + 1, y + 2, 1, 1, SHINE);
        }
        if (!inside(col, row - 1)) r(g, x, y, T, 3, WALL_SHADOW); // sombra del muro de arriba
    }

    /** Alfombra roja de torneo: borde dorado solo hacia donde termina. */
    private static void carpet(GraphicsContext g, int col, int row) {
        int x = col * T, y = row * T;
        r(g, x, y, T, T, CARPET);
        // rombo dorado en el centro de cada casilla
        r(g, x + 7, y + 6, 2, 1, GOLD_DARK);
        r(g, x + 6, y + 7, 4, 2, GOLD_DARK);
        r(g, x + 7, y + 9, 2, 1, GOLD_DARK);
        r(g, x + 7, y + 7, 2, 2, GOLD);
        boolean up = isCarpet(col, row - 1) && inside(col, row - 1), down = isCarpet(col, row + 1) && inside(col, row + 1);
        boolean left = isCarpet(col - 1, row) && inside(col - 1, row), right = isCarpet(col + 1, row) && inside(col + 1, row);
        if (!up) { r(g, x, y, T, 1, CARPET_DARK); r(g, x, y + 2, T, 2, GOLD); }
        if (!down) { r(g, x, y + T - 4, T, 2, GOLD); r(g, x, y + T - 1, T, 1, CARPET_DARK); }
        if (!left) { r(g, x, y, 1, T, CARPET_DARK); r(g, x + 2, y, 2, T, GOLD); }
        if (!right) { r(g, x + T - 4, y, 2, T, GOLD); r(g, x + T - 1, y, 1, T, CARPET_DARK); }
    }

    /** Duela de madera: tablas de 4 px con uniones escalonadas. */
    private static void woodFloor(GraphicsContext g, int col, int row) {
        int x = col * T, y = row * T;
        r(g, x, y, T, T, WOOD);
        for (int k = 0; k < 4; k++) {
            int py = y + k * 4;
            r(g, x, py, T, 1, WOOD_SHINE);
            r(g, x, py + 3, T, 1, WOOD_LINE);
            int joint = (col * 7 + k * 5 + row * 3) % T;
            r(g, x + joint, py, 1, 3, WOOD_LINE);
        }
        if (!inside(col, row - 1)) r(g, x, y, T, 3, WALL_SHADOW);
    }

    private static void metalFloor(GraphicsContext g, int x, int y) {
        r(g, x, y, T, T, Color.rgb(58, 70, 98));
        r(g, x, y, T, 1, Color.rgb(78, 92, 124));
        r(g, x + T - 1, y, 1, T, Color.rgb(42, 52, 76));
        r(g, x + 3, y + 3, 1, 1, Color.rgb(96, 110, 144));
        r(g, x + 12, y + 12, 1, 1, Color.rgb(96, 110, 144));
    }

    // ------------------------------------------------------------------ muros

    private static void wall(GraphicsContext g, int col, int row) {
        boolean near = false;
        for (int dy = -1; dy <= 1 && !near; dy++)
            for (int dx = -1; dx <= 1; dx++)
                if (inside(col + dx, row + dy)) { near = true; break; }
        if (!near) return; // fuera del edificio: vacío
        if (inside(col, row + 1)) {
            wallFace(g, col, row);
            return;
        }
        int x = col * T, y = row * T;
        r(g, x, y, T, T, WALL_TOP);
        if (inside(col, row - 1)) r(g, x, y, T, 3, WALL_EDGE);
        if (inside(col + 1, row)) r(g, x + T - 3, y, 3, T, WALL_EDGE);
        if (inside(col - 1, row)) r(g, x, y, 3, T, WALL_EDGE);
    }

    /** Muro visto de frente (tiene piso debajo): borde superior, paneles, franja de luz y zócalo. */
    private static void wallFace(GraphicsContext g, int col, int row) {
        int x = col * T, y = row * T;
        r(g, x, y, T, T, WALL_TOP);
        r(g, x, y + 3, T, 1, WALL_EDGE);
        boolean training = inTraining(col, row + 1);
        r(g, x, y + 4, T, 10, training ? Color.rgb(196, 204, 220) : FACE);
        r(g, x, y + 8, T, 1, training ? Color.rgb(222, 228, 238) : FACE_LIGHT);
        r(g, x, y + 4, 1, 10, training ? Color.rgb(170, 178, 196) : FACE_SEAM);
        r(g, x, y + 14, T, 2, BASEBOARD);
        if (inside(col - 1, row)) r(g, x, y, 3, T, WALL_EDGE);
        if (inside(col + 1, row)) r(g, x + T - 3, y, 3, T, WALL_EDGE);

        if (training && row == 5) mirror(g, x, y);
        if (row == 12 && (col == 14 || col == 20 || col == 32 || col == 38 || col == 42)) {
            banner(g, x, y, (col / 6) % 2 == 0 ? Color.rgb(196, 48, 56) : Color.rgb(44, 96, 190));
        }
        if (row == 12 && (col == 16 || col == 22 || col == 30 || col == 36 || col == 40)) sconce(g, x, y);
    }

    private static void banner(GraphicsContext g, int x, int y, Color cloth) {
        Color shade = cloth.darker();
        r(g, x + 3, y + 1, 10, 1, GOLD);
        r(g, x + 4, y + 2, 8, 10, cloth);
        r(g, x + 11, y + 2, 1, 10, shade);
        r(g, x + 4, y + 12, 3, 1, cloth);
        r(g, x + 9, y + 12, 3, 1, cloth);
        // emblema: estrella de 4 puntas
        r(g, x + 7, y + 4, 2, 1, GOLD);
        r(g, x + 6, y + 5, 4, 2, GOLD);
        r(g, x + 5, y + 6, 6, 1, GOLD);
        r(g, x + 7, y + 7, 2, 2, GOLD);
    }

    private static void sconce(GraphicsContext g, int x, int y) {
        r(g, x + 6, y + 6, 4, 3, GOLD);
        r(g, x + 7, y + 5, 2, 1, Color.rgb(255, 240, 180));
        r(g, x + 7, y + 9, 2, 1, GOLD_DARK);
    }

    private static void mirror(GraphicsContext g, int x, int y) {
        r(g, x + 1, y + 4, 14, 9, Color.rgb(176, 220, 240));
        r(g, x + 3, y + 5, 1, 3, Color.WHITE);
        r(g, x + 4, y + 8, 1, 2, Color.WHITE);
        r(g, x + 1, y + 12, 14, 1, Color.rgb(130, 170, 200));
    }

    // ------------------------------------------------------------------ mostrador, NPC y pantallas

    private static void counter(GraphicsContext g, int col, int row) {
        int x = col * T, y = row * T;
        r(g, x, y, T, 5, Color.rgb(226, 236, 248));
        r(g, x, y + 5, T, 1, WALL_EDGE);
        r(g, x, y + 6, T, 10, Color.rgb(34, 96, 168));
        r(g, x, y + 9, T, 2, Color.rgb(70, 170, 230));
        r(g, x, y + 15, T, 1, Color.rgb(18, 40, 80));
        if (col == 22 || col == 30) { // monitor de atención sobre el mostrador
            r(g, x + 3, y - 2, 10, 5, DARK_METAL);
            r(g, x + 4, y - 1, 8, 3, SCREEN);
        }
    }

    /** Personal del mostrador (se ve de la cintura para arriba: el mostrador tapa el resto). */
    private static void npc(GraphicsContext g, int x, int y, Color jacket) {
        String[] art = {
                "....hhhh....",
                "...hhhhhh...",
                "...hssssh...",
                "...sesses...",
                "...ssssss...",
                "....ssss....",
                "..jjjwwjjj..",
                ".jjjjwwjjjj.",
                ".jjjjjjjjjj.",
                ".sjjjjjjjjs.",
                ".sjjjjjjjjs.",
                "..jjjjjjjj..",
        };
        int ox = x + 2, oy = y + T - art.length;
        for (int i = 0; i < art.length; i++) {
            for (int j = 0; j < art[i].length(); j++) {
                Color c = switch (art[i].charAt(j)) {
                    case 'h' -> Color.rgb(70, 46, 30);
                    case 's' -> Color.rgb(240, 200, 168);
                    case 'e' -> Color.rgb(30, 30, 40);
                    case 'j' -> jacket;
                    case 'w' -> Color.WHITE;
                    default -> null;
                };
                if (c != null) r(g, ox + j, oy + i, 1, 1, c);
            }
        }
    }

    /** Gran marcador "VS ONLINE" sobre el mostrador (muro norte del embudo). */
    private static void drawScoreboard(GraphicsContext g) {
        int x = 22 * T + 2, y = 3 * T - 26, w = 9 * T - 4, h = 24;
        r(g, x - 1, y - 1, w + 2, h + 2, WALL_EDGE);
        r(g, x, y, w, h, Color.rgb(24, 30, 46));
        r(g, x + 2, y + 2, w - 4, h - 4, Color.rgb(10, 34, 62));
        int textW = pixelTextWidth("VS ONLINE", 2);
        int tx = x + (w - textW) / 2, ty = y + 5;
        pixelText(g, "VS", tx, ty, 2, Color.rgb(255, 210, 70));
        pixelText(g, "ONLINE", tx + pixelTextWidth("VS ", 2), ty, 2, SCREEN);
        for (int i = 0; i < 4; i++) r(g, x + 6 + i * 3, y + h - 4, 1, 1, Color.rgb(255, 90, 90));
    }

    /** Tabla de posiciones: kiosco con podio 1-2-3. */
    private static void leaderboard(GraphicsContext g, int x, int y) {
        r(g, x + 1, y + 1, 30, 14, Color.rgb(30, 40, 60));
        r(g, x + 3, y + 2, 26, 10, Color.rgb(14, 48, 84));
        Color[] medals = {Color.rgb(255, 210, 70), Color.rgb(200, 210, 224), Color.rgb(214, 140, 80)};
        for (int i = 0; i < 3; i++) {
            r(g, x + 5, y + 3 + i * 3, 2, 2, medals[i]);
            r(g, x + 9, y + 3 + i * 3, 17 - i * 4, 2, SCREEN);
        }
        r(g, x + 3, y + 13, 26, 2, Color.rgb(60, 70, 90));
    }

    private static void pc(GraphicsContext g, int x, int y) {
        r(g, x + 4, y + 1, 24, 10, DARK_METAL);
        r(g, x + 5, y + 2, 22, 8, Color.rgb(40, 110, 220));
        r(g, x + 7, y + 3, 9, 5, Color.rgb(220, 235, 255));
        r(g, x + 7, y + 3, 9, 1, Color.rgb(255, 180, 60));
        r(g, x + 18, y + 4, 7, 1, Color.rgb(150, 200, 255));
        r(g, x + 18, y + 6, 5, 1, Color.rgb(150, 200, 255));
        r(g, x + 14, y + 11, 4, 2, Color.rgb(70, 74, 90));
        r(g, x + 8, y + 13, 16, 2, Color.rgb(200, 206, 220));
    }

    // ------------------------------------------------------------------ Training Room, portal, entrada

    private static void drawTrainingMats(GraphicsContext g) {
        int x = 3 * T + 2, y = 13 * T + 2, w = 6 * T - 4, h = 5 * T - 4;
        r(g, x, y, w, h, MAT);
        r(g, x + 2, y + 2, w - 4, 1, MAT_LINE);
        r(g, x + 2, y + h - 3, w - 4, 1, MAT_LINE);
        r(g, x + 2, y + 2, 1, h - 4, MAT_LINE);
        r(g, x + w - 3, y + 2, 1, h - 4, MAT_LINE);
        // círculo central (anillo pixelado)
        int cx = x + w / 2, cy = y + h / 2;
        r(g, cx - 6, cy - 12, 12, 1, MAT_LINE);
        r(g, cx - 6, cy + 11, 12, 1, MAT_LINE);
        r(g, cx - 12, cy - 6, 1, 12, MAT_LINE);
        r(g, cx + 11, cy - 6, 1, 12, MAT_LINE);
        for (int i = 0; i < 6; i++) {
            r(g, cx - 12 + i, cy - 6 - i, 1, 1, MAT_LINE);
            r(g, cx + 11 - i, cy - 6 - i, 1, 1, MAT_LINE);
            r(g, cx - 12 + i, cy + 5 + i, 1, 1, MAT_LINE);
            r(g, cx + 11 - i, cy + 5 + i, 1, 1, MAT_LINE);
        }
        r(g, x, y + h, w, 1, Color.rgb(30, 60, 110));
    }

    /** Plataforma del portal decorativo (el remolino animado va encima, en drawPortalAnimation). */
    private static void drawPortalBase(GraphicsContext g) {
        int cx = (int) PORTAL_CX, by = 17 * T + 6;
        // plataforma escalonada (elipse pixelada)
        int[][] steps = {{18, 0}, {24, 1}, {28, 2}, {28, 3}, {28, 4}, {26, 5}, {22, 6}, {16, 7}};
        for (int[] s : steps) r(g, cx - s[0], by - 12 + s[1], s[0] * 2, 1, Color.rgb(96, 114, 150));
        for (int[] s : steps) r(g, cx - s[0] + 3, by - 4 + s[1] / 2, s[0] * 2 - 6, 1, Color.rgb(54, 66, 96));
        r(g, cx - 24, by - 11, 48, 1, WALL_EDGE);
        // líneas de luz del suelo hacia el portal
        for (int col = 45; col <= 45; col++) {
            r(g, col * T, 15 * T + 7, T, 2, Color.rgb(90, 200, 250, 0.55));
        }
        // dos cristales a los lados
        crystal(g, 46 * T + 2, 17 * T - 2);
        crystal(g, 48 * T + 9, 17 * T - 2);
    }

    private static void crystal(GraphicsContext g, int x, int y) {
        r(g, x + 1, y, 3, 1, Color.rgb(200, 250, 255));
        r(g, x, y + 1, 5, 6, WALL_EDGE);
        r(g, x + 1, y + 1, 1, 5, Color.rgb(200, 250, 255));
        r(g, x + 1, y + 7, 3, 1, Color.rgb(40, 120, 180));
    }

    private static void entranceMat(GraphicsContext g, int col, int x, int y) {
        r(g, x, y + 2, T, 12, Color.rgb(60, 66, 82));
        if (col == 24) r(g, x + 1, y + 2, 1, 12, Color.rgb(40, 44, 56));
        if (col == 28) r(g, x + T - 2, y + 2, 1, 12, Color.rgb(40, 44, 56));
        // flecha hacia adentro (arriba)
        Color c = Color.rgb(90, 200, 250, 0.8);
        r(g, x + 7, y + 4, 2, 1, c);
        r(g, x + 6, y + 5, 4, 1, c);
        r(g, x + 5, y + 6, 6, 1, c);
        r(g, x + 7, y + 7, 2, 5, c);
    }

    /** Puertas corredizas de vidrio en el muro sur de la entrada. */
    private static void drawEntranceDoors(GraphicsContext g) {
        int x = 24 * T, y = 29 * T, w = 5 * T;
        r(g, x, y, w, 7, Color.rgb(150, 160, 180));
        r(g, x + 2, y + 1, w / 2 - 3, 5, Color.rgb(150, 210, 240));
        r(g, x + w / 2 + 1, y + 1, w / 2 - 3, 5, Color.rgb(150, 210, 240));
        r(g, x + w / 2 - 1, y, 2, 7, Color.rgb(90, 100, 120));
        r(g, x + 6, y + 2, 4, 1, Color.WHITE);
        r(g, x + w / 2 + 8, y + 2, 4, 1, Color.WHITE);
    }

    // ------------------------------------------------------------------ adornos

    private static void drawProp(GraphicsContext g, LobbyMap.Prop p) {
        int x = p.col() * T, y = p.row() * T;
        switch (p.kind()) {
            case "planta" -> {
                r(g, x + 3, y + 14, 10, 2, SHADOW);
                r(g, x + 3, y + 8, 10, 2, Color.rgb(178, 104, 64));
                r(g, x + 4, y + 10, 8, 5, Color.rgb(150, 84, 52));
                r(g, x + 10, y + 10, 2, 5, Color.rgb(120, 66, 40));
                Color leaf = Color.rgb(52, 150, 72), dark = Color.rgb(34, 110, 52), light = Color.rgb(110, 200, 110);
                r(g, x + 5, y + 2, 6, 6, leaf);
                r(g, x + 2, y + 4, 4, 4, leaf);
                r(g, x + 10, y + 4, 4, 4, leaf);
                r(g, x + 7, y - 1, 2, 4, leaf);
                r(g, x + 6, y + 5, 2, 2, dark);
                r(g, x + 9, y + 3, 2, 2, dark);
                r(g, x + 3, y + 6, 2, 1, dark);
                r(g, x + 5, y + 3, 2, 1, light);
                r(g, x + 8, y, 1, 1, light);
                r(g, x + 12, y + 5, 1, 1, light);
            }
            case "banca" -> {
                int w = p.w() * T;
                r(g, x + 2, y + 13, w - 4, 2, SHADOW);
                r(g, x + 1, y + 1, w - 2, 3, Color.rgb(146, 92, 50));
                r(g, x + 1, y + 5, w - 2, 4, Color.rgb(170, 112, 64));
                r(g, x + 1, y + 5, w - 2, 1, Color.rgb(206, 150, 96));
                r(g, x + 3, y + 9, 2, 5, DARK_METAL);
                r(g, x + w - 5, y + 9, 2, 5, DARK_METAL);
                r(g, x + 3, y + 4, 2, 1, DARK_METAL);
                r(g, x + w - 5, y + 4, 2, 1, DARK_METAL);
            }
            case "vitrina" -> {
                r(g, x + 1, y + 10, 14, 5, Color.rgb(70, 76, 96));
                r(g, x + 2, y + 1, 12, 9, Color.rgb(170, 220, 240));
                r(g, x + 1, y, 14, 1, Color.rgb(200, 206, 220));
                r(g, x + 1, y, 1, 10, Color.rgb(200, 206, 220));
                r(g, x + 14, y, 1, 10, Color.rgb(200, 206, 220));
                r(g, x + 6, y + 3, 4, 3, Color.rgb(240, 190, 50));
                r(g, x + 5, y + 3, 1, 2, Color.rgb(240, 190, 50));
                r(g, x + 10, y + 3, 1, 2, Color.rgb(240, 190, 50));
                r(g, x + 7, y + 6, 2, 2, GOLD_DARK);
                r(g, x + 6, y + 8, 4, 1, GOLD_DARK);
                r(g, x + 3, y + 2, 1, 3, Color.WHITE);
            }
            case "saco" -> {
                r(g, x + 4, y + 14, 8, 2, SHADOW);
                r(g, x + 7, y - 5, 2, 5, METAL);
                r(g, x + 4, y, 8, 13, Color.rgb(196, 52, 52));
                r(g, x + 5, y + 1, 2, 11, Color.rgb(232, 96, 90));
                r(g, x + 10, y + 1, 2, 11, Color.rgb(150, 36, 40));
                r(g, x + 4, y + 3, 8, 1, Color.rgb(60, 40, 40));
                r(g, x + 4, y + 10, 8, 1, Color.rgb(60, 40, 40));
            }
            case "pesas" -> {
                r(g, x + 2, y + 1, 12, 30, Color.rgb(70, 74, 90));
                Color[] plates = {Color.rgb(200, 50, 50), Color.rgb(50, 90, 200), Color.rgb(230, 190, 50), Color.rgb(40, 40, 48)};
                for (int i = 0; i < 4; i++) {
                    int py = y + 3 + i * 7;
                    r(g, x + 1, py + 2, 14, 1, Color.rgb(190, 190, 200));
                    r(g, x + 2, py, 3, 5, plates[i]);
                    r(g, x + 11, py, 3, 5, plates[i]);
                }
            }
            case "mancuernas" -> {
                r(g, x + 3, y + 2, 10, 28, Color.rgb(90, 70, 52));
                for (int i = 0; i < 4; i++) {
                    int py = y + 4 + i * 6;
                    r(g, x + 4, py, 2, 4, DARK_METAL);
                    r(g, x + 10, py, 2, 4, DARK_METAL);
                    r(g, x + 6, py + 1, 4, 2, Color.rgb(180, 180, 190));
                }
            }
            case "dispensador" -> {
                r(g, x + 4, y + 14, 8, 2, SHADOW);
                r(g, x + 4, y + 6, 8, 9, Color.rgb(220, 226, 236));
                r(g, x + 5, y, 6, 6, Color.rgb(120, 190, 240));
                r(g, x + 6, y + 1, 1, 4, Color.rgb(200, 235, 255));
                r(g, x + 5, y + 9, 2, 1, Color.rgb(220, 60, 60));
                r(g, x + 9, y + 9, 2, 1, Color.rgb(60, 110, 220));
            }
            case "muñeco" -> {
                r(g, x + 4, y + 14, 8, 2, Color.rgb(80, 56, 36));
                r(g, x + 7, y + 8, 2, 6, Color.rgb(120, 80, 50));
                r(g, x + 4, y + 2, 8, 7, Color.rgb(196, 150, 96));
                r(g, x + 1, y + 4, 3, 2, Color.rgb(196, 150, 96));
                r(g, x + 12, y + 4, 3, 2, Color.rgb(196, 150, 96));
                r(g, x + 5, y - 3, 6, 5, Color.rgb(210, 166, 110));
                r(g, x + 6, y + 3, 4, 4, Color.rgb(200, 50, 50));
                r(g, x + 7, y + 4, 2, 2, Color.rgb(240, 230, 220));
            }
            case "trotadora" -> {
                int w = p.w() * T;
                r(g, x + 2, y + 13, w - 4, 2, SHADOW);
                r(g, x + 1, y + 5, w - 2, 9, Color.rgb(150, 156, 170));
                r(g, x + 2, y + 6, w - 8, 7, Color.rgb(40, 42, 52));
                for (int i = 0; i < 4; i++) r(g, x + 4 + i * 6, y + 6, 1, 7, Color.rgb(70, 72, 84));
                r(g, x + w - 6, y, 5, 8, Color.rgb(100, 106, 120));
                r(g, x + w - 5, y + 1, 3, 2, SCREEN);
                r(g, x + w - 12, y + 2, 7, 1, Color.rgb(170, 176, 190));
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ texto pixel (5x5) y etiquetas

    /** Letras de 5x5 (solo las del marcador). */
    private static final String GLYPHS = "EILNOSV ";
    private static final String[][] FONT = {
            {"11111", "1....", "1111.", "1....", "11111"}, // E
            {"11111", "..1..", "..1..", "..1..", "11111"}, // I
            {"1....", "1....", "1....", "1....", "11111"}, // L
            {"1...1", "11..1", "1.1.1", "1..11", "1...1"}, // N
            {"11111", "1...1", "1...1", "1...1", "11111"}, // O
            {"11111", "1....", "11111", "....1", "11111"}, // S
            {"1...1", "1...1", "1...1", ".1.1.", "..1.."}, // V
            {".....", ".....", ".....", ".....", "....."}, // espacio
    };

    private static int pixelTextWidth(String text, int scale) {
        return text.length() * 6 * scale - scale;
    }

    private static void pixelText(GraphicsContext g, String text, int x, int y, int scale, Color color) {
        for (int i = 0; i < text.length(); i++) {
            int idx = GLYPHS.indexOf(text.charAt(i));
            if (idx < 0) continue;
            String[] glyph = FONT[idx];
            for (int row = 0; row < 5; row++)
                for (int col = 0; col < 5; col++)
                    if (glyph[row].charAt(col) == '1') r(g, x + (i * 6 + col) * scale, y + row * scale, scale, scale, color);
        }
    }

    private static void label(GraphicsContext g, String text, double cx, double y) {
        g.setFont(Font.font("Consolas", FontWeight.BOLD, 11));
        // Consolas 11 ~ 6.1 px por letra: nunca dejar que la etiqueta se salga del mapa.
        double half = text.length() * 6.1 / 2 + 4;
        cx = Math.max(half, Math.min(LobbyMap.WIDTH - half, cx));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.rgb(0, 0, 0, 0.6));
        g.fillText(text, cx + 1, y + 1);
        g.setFill(LABEL);
        g.fillText(text, cx, y);
    }

    private static void r(GraphicsContext g, double x, double y, double w, double h, Color c) {
        g.setFill(c);
        g.fillRect(x, y, w, h);
    }

    // ------------------------------------------------------------------ globo de aviso

    /** Ancho del globo de aviso (en las unidades donde se dibuje). */
    public static final double BUBBLE_WIDTH = 230;

    /** Globo con el aviso del lugar/NPC cercano, con su esquina superior izquierda en (x, y), en coordenadas de PANTALLA. */
    public static void drawBubble(GraphicsContext g, LobbyMap.Feature f, double x, double y) {
        List<String> lines = wrap(f.message(), 34);
        double w = BUBBLE_WIDTH, lineH = 14, h = lines.size() * lineH + 12;
        g.setFill(Color.rgb(255, 255, 255, 0.95));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setStroke(Color.rgb(30, 60, 110));
        g.strokeRoundRect(x, y, w, h, 12, 12);
        g.setFill(Color.rgb(20, 30, 50));
        g.setFont(Font.font("Consolas", 11));
        g.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < lines.size(); i++) g.fillText(lines.get(i), x + 8, y + 16 + i * lineH);
    }

    private static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > maxChars && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
