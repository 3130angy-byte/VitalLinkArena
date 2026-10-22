package org.example.online;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mapa del 1er piso del Edificio Torneo, compartido por servidor y cliente
 * (el servidor lo usa para colisiones y caminos; el cliente para dibujar).
 *
 * FORMA basada en el Colosseum 1F - Lobby de Digimon World Re:Digitize
 * (referencia del usuario): pasillo largo oeste-este; nicho norte en forma
 * de embudo con 2 NPC (Ranking Battle / Battle Reception); Digistorage junto
 * al embudo; sala en cruz al oeste; salida al sur (File City) y otra al este.
 * Adaptación a nuestro diseño:
 *   - Ranking Battle   -> NPC de Batalla oficial (ranking)
 *   - Battle Reception -> NPC de Recepción de torneos
 *   - Digistorage      -> terminal de la tabla de posiciones (no hay almacenamiento)
 *   - sala en cruz     -> Training Room: ABIERTA, solo escena (decisión del usuario, 2026-10-01)
 *   - salida este      -> portal: solo decorativo (no lleva a ningún lado)
 *   - salida sur       -> entrada: aquí aparecen los jugadores
 * ESTILO del arte: el boceto "Edificio Torneo - 1er piso" del usuario. Por
 * ahora el cliente lo dibuja con figuras simples; el arte final será una
 * imagen sobre esta misma cuadrícula (la forma se confirma primero).
 */
public final class LobbyMap {

    public static final String ID = "torneo-1f-v2";
    public static final int TILE = 16;
    public static final int COLS = 50;
    public static final int ROWS = 31;
    public static final int WIDTH = COLS * TILE;   // 800
    public static final int HEIGHT = ROWS * TILE;  // 496

    // Tipos de casilla.
    public static final char WALL = '#';
    public static final char FLOOR = '.';
    public static final char TRAINING_FLOOR = 't';
    public static final char COUNTER = 'C';
    public static final char NPC_RANKING = 'A';
    public static final char NPC_RECEPTION = 'B';
    public static final char TERMINAL = 'T';
    /** PC (como en Re:Digitize): conecta con el Laboratorio para elegir el equipo (puestos 1 y 2). */
    public static final char PC = 'K';
    public static final char PORTAL = 'P';
    /**
     * Pantalla grande "COMBATES EN VIVO" en el muro norte del pasillo (v13): al acercarse se eligen las peleas que
     * están ocurriendo para mirarlas. Es parte del muro: no cambia por dónde se camina (servidores y clientes viejos
     * calculan los mismos caminos).
     */
    public static final char SCREEN = 'V';
    /** Mueble o adorno (macetas, bancas, sacos de boxeo...): se dibuja encima del piso y no se pisa. */
    public static final char PROP = 'D';
    public static final char ENTRANCE = 'E';
    /** Piso detrás del mostrador (zona de los NPC): se dibuja como piso pero no se pisa. */
    public static final char STAFF_FLOOR = 's';

    /** Un punto de interés: el cliente muestra su aviso al acercarse. */
    public record Feature(char type, String label, String message, double x, double y) {}

    /**
     * Adornos (solo escena, sin funciones): tipo, casilla y tamaño en
     * casillas. El servidor solo necesita saber que no se pisan; el cliente
     * los dibuja según su tipo (LobbyMapRenderer).
     */
    public record Prop(String kind, int col, int row, int w, int h) {}

    public static final List<Prop> PROPS = List.of(
            // Pasillo principal: macetas y bancas
            new Prop("planta", 12, 13, 1, 1), new Prop("planta", 22, 13, 1, 1),
            new Prop("planta", 30, 13, 1, 1), new Prop("planta", 44, 13, 1, 1),
            new Prop("planta", 12, 17, 1, 1), new Prop("planta", 44, 17, 1, 1),
            new Prop("banca", 16, 17, 2, 1), new Prop("banca", 35, 17, 2, 1),
            // Vitrinas de trofeos junto al mostrador
            new Prop("vitrina", 20, 6, 1, 1), new Prop("vitrina", 32, 6, 1, 1),
            // Entrada
            new Prop("planta", 24, 27, 1, 1), new Prop("planta", 28, 27, 1, 1),
            // Training Room
            new Prop("saco", 4, 7, 1, 1), new Prop("saco", 7, 7, 1, 1),
            new Prop("pesas", 1, 12, 1, 2), new Prop("mancuernas", 1, 17, 1, 2),
            new Prop("dispensador", 8, 6, 1, 1),
            new Prop("muñeco", 9, 11, 1, 1), new Prop("muñeco", 9, 19, 1, 1),
            new Prop("trotadora", 3, 23, 2, 1), new Prop("trotadora", 7, 23, 2, 1)
    );

    private static final char[][] GRID = build();

    public static final List<Feature> FEATURES = List.of(
            new Feature(NPC_RANKING, "Batalla oficial",
                    "¡Hola! Aquí podrás pelear Batallas Oficiales de ranking. (Próximamente)",
                    center(21), center(4)),
            new Feature(NPC_RECEPTION, "Recepción de torneos",
                    "¡Hola! Aquí creas torneos y te inscribes en los de los demás.",
                    center(31), center(4)),
            new Feature(TERMINAL, "Tabla de posiciones",
                    "Tabla de posiciones: las victorias de cada Digimon este mes.",
                    (17 + 1) * TILE, center(12)),
            new Feature(SCREEN, "Combates en vivo",
                    "Pantalla de la sala: mira las peleas que están ocurriendo.",
                    (39 + 1.5) * TILE, center(12)),
            new Feature(PC, "PC",
                    "PC: abre tu Laboratorio para elegir tu equipo (puesto 1 y 2).",
                    (34 + 1) * TILE, center(12))
    );

    private LobbyMap() {}

    private static char[][] build() {
        char[][] g = new char[ROWS][COLS];
        for (char[] row : g) java.util.Arrays.fill(row, WALL);

        fill(g, 11, 13, 44, 17, FLOOR);          // pasillo principal oeste-este
        fill(g, 20, 4, 32, 6, FLOOR);             // boca ancha del embudo norte
        fill(g, 20, 4, 32, 4, STAFF_FLOOR);       // detrás del mostrador: solo los NPC
        fill(g, 23, 7, 29, 12, FLOOR);            // cuello del embudo
        fill(g, 20, 5, 32, 5, COUNTER);           // mostrador de muro a muro: nadie pasa detrás
        g[4][21] = NPC_RANKING;   // en los extremos del mostrador, como los 2 NPC de Re:Digitize
        g[4][31] = NPC_RECEPTION;
        fill(g, 24, 18, 28, 28, FLOOR);           // pasillo sur hacia la entrada
        fill(g, 24, 28, 28, 28, ENTRANCE);
        fill(g, 3, 6, 8, 24, TRAINING_FLOOR);     // sala en cruz (brazo vertical)
        fill(g, 1, 11, 9, 19, TRAINING_FLOOR);    // sala en cruz (brazo horizontal)
        fill(g, 10, 13, 10, 17, FLOOR);           // entrada abierta del Training Room
        fill(g, 45, 12, 45, 18, FLOOR);           // antesala del portal
        fill(g, 46, 13, 48, 17, PORTAL);
        fill(g, 17, 12, 18, 12, TERMINAL);        // terminal en el muro norte
        fill(g, 34, 12, 35, 12, PC);              // PC en el muro norte, frente a la terminal
        fill(g, 39, 12, 41, 12, SCREEN);          // pantalla de combates en vivo (v13), también en el muro
        for (Prop p : PROPS) fill(g, p.col(), p.row(), p.col() + p.w() - 1, p.row() + p.h() - 1, PROP);
        return g;
    }

    private static void fill(char[][] g, int x0, int y0, int x1, int y1, char c) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) g[y][x] = c;
    }

    private static double center(int tile) {
        return tile * TILE + TILE / 2.0;
    }

    public static char at(int col, int row) {
        if (col < 0 || row < 0 || col >= COLS || row >= ROWS) return WALL;
        return GRID[row][col];
    }

    /** Se pisan el piso, la entrada y el piso del Training Room (abierto); nunca muros, mostrador, adornos ni el portal. */
    public static boolean walkable(int col, int row) {
        char c = at(col, row);
        return c == FLOOR || c == ENTRANCE || c == TRAINING_FLOOR;
    }

    public static boolean walkableAt(double x, double y) {
        return walkable((int) Math.floor(x / TILE), (int) Math.floor(y / TILE));
    }

    /** Casillas de entrada (donde aparecen los jugadores), en coordenadas del mundo. */
    public static List<double[]> spawnPoints() {
        List<double[]> points = new ArrayList<>();
        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++)
                if (GRID[y][x] == ENTRANCE) points.add(new double[]{center(x), center(y)});
        return points;
    }

    /**
     * Camino por casillas (8 direcciones, sin cortar esquinas) desde un punto
     * del mundo hasta otro. Si el destino no se puede alcanzar (un NPC, un
     * muro, una sala cerrada), va a la casilla ALCANZABLE más cercana al
     * clic. Devuelve los centros de casilla a recorrer (sin la de partida);
     * vacío si ya está en el punto más cercano posible.
     */
    public static List<double[]> findPath(double fromX, double fromY, double toX, double toY) {
        int sx = (int) Math.floor(fromX / TILE), sy = (int) Math.floor(fromY / TILE);
        if (!walkable(sx, sy)) return List.of();
        int tx = (int) Math.floor(toX / TILE), ty = (int) Math.floor(toY / TILE);

        // BFS completa desde el jugador: el mapa es chico (50x31), es barato.
        int[][] prev = new int[ROWS * COLS][];
        boolean[] seen = new boolean[ROWS * COLS];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy});
        seen[sy * COLS + sx] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] d : dirs) {
                int nx = cur[0] + d[0], ny = cur[1] + d[1];
                if (!walkable(nx, ny) || seen[ny * COLS + nx]) continue;
                // En diagonal, ambas casillas laterales deben ser pisables (no atravesar esquinas).
                if (d[0] != 0 && d[1] != 0 && (!walkable(cur[0] + d[0], cur[1]) || !walkable(cur[0], cur[1] + d[1]))) continue;
                seen[ny * COLS + nx] = true;
                prev[ny * COLS + nx] = cur;
                queue.add(new int[]{nx, ny});
            }
        }

        int gx = sx, gy = sy;
        double best = Double.MAX_VALUE;
        for (int y = 0; y < ROWS; y++)
            for (int x = 0; x < COLS; x++)
                if (seen[y * COLS + x]) {
                    double d = Math.hypot(x - tx, y - ty);
                    if (d < best) { best = d; gx = x; gy = y; }
                }

        List<double[]> path = new ArrayList<>();
        int[] cur = {gx, gy};
        while (!(cur[0] == sx && cur[1] == sy)) {
            path.add(new double[]{center(cur[0]), center(cur[1])});
            cur = prev[cur[1] * COLS + cur[0]];
        }
        Collections.reverse(path);
        // Si el clic cayó en una casilla alcanzable, el último punto es el lugar exacto del clic.
        if (gx == tx && gy == ty && walkableAt(toX, toY)) {
            if (path.isEmpty()) path.add(new double[]{toX, toY});
            else path.set(path.size() - 1, new double[]{toX, toY});
        }
        return path;
    }

    /** Vista en texto del mapa (depuración y para revisar la forma sin abrir el juego). */
    public static String toAscii() {
        StringBuilder sb = new StringBuilder();
        for (char[] row : GRID) sb.append(row).append('\n');
        return sb.toString();
    }
}
