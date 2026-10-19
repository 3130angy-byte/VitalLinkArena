package org.example.dim;

import com.github.cfogrady.vb.dim.sprite.SpriteData;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lee el nombre escrito en el sprite NAME de una DIM (pedido del
 * usuario, 2026-10-02): compara los píxeles de tinta con las letras de las 3
 * fuentes del Vital Bracelet que dio el usuario (hojas con las letras separadas
 * por columnas magenta, en resources/fonts/VB_Alphabet_ENG_*.png; sus plantillas
 * en texto están en resources/fonts/vb_name_fonts.txt):
 *  - DigiScript: mayúsculas Y minúsculas (es la que usan las DIM reales vistas:
 *    "MagnaKidmon", "DYNASMON").
 *  - Official Bandai y Agero: solo mayúsculas.
 *
 * Cómo: el sprite se pasa a tinta / fondo (el verde puro es transparente). Para
 * cada fuente y cada desplazamiento vertical, una programación dinámica busca
 * la forma más barata de cubrir las columnas con letras (costo = píxeles que
 * no coinciden; saltar una columna con tinta cuesta el doble). Así funciona aunque
 * dos letras queden pegadas o el texto esté 1-2 filas más arriba o abajo. Un hueco
 * ancho entre letras = espacio. Gana la fuente con menos error; si el error es
 * alto (p. ej. katakana), no se lee nada.
 *
 * Nota (CLAUDE.md, decisión anterior): en 2026-09 se descartó el OCR por plantillas
 * porque la fuente de DIMNameGen (= Official Bandai) no coincidía con los sprites
 * reales; con la fuente DigiScript que trajo el usuario sí coinciden.
 */
public final class NameSpriteReader {

    /** Error máximo aceptado (píxeles que no coinciden / tinta del sprite). */
    public static final double MAX_ERROR = 0.12;
    private static final int ROWS = 15;
    private static final int MAX_SHIFT = 3;
    /** Columnas vacías seguidas entre dos letras que ya cuentan como un espacio. */
    private static final int SPACE_GAP = 3;
    private static final int SKIP_INK_COST = 2;

    /** Lo leído, con qué fuente y cuánto error (0 = idéntico). */
    public record Result(String text, String font, double error) {}

    private record Glyph(String symbol, int width, boolean[][] ink) {}

    private record Font(String name, boolean lowercase, List<Glyph> glyphs) {}

    private static final List<Font> FONTS = loadFonts();

    private NameSpriteReader() {}

    /** La especie escrita en el sprite NAME de una VS DIM (vacío si no se puede leer, p. ej. katakana). */
    public static Optional<String> speciesOf(java.nio.file.Path vsDim) {
        try {
            return read(VsDimReader.read(vsDim).sprites().get(0)).map(Result::text);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Lee un sprite NAME de la DIM (RGB565 crudo; el verde puro es transparente). */
    public static Optional<Result> read(SpriteData.Sprite sprite) {
        if (sprite == null) return Optional.empty();
        int w = sprite.getWidth(), h = sprite.getHeight();
        byte[] px = sprite.getPixelData();
        boolean[][] ink = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 2;
                int v = (px[i] & 0xff) | ((px[i + 1] & 0xff) << 8);
                if (v == 0x07E0) continue; // verde puro = transparente
                double r = ((v >> 11) & 31) / 31.0, g = ((v >> 5) & 63) / 63.0, b = (v & 31) / 31.0;
                ink[y][x] = 0.299 * r + 0.587 * g + 0.114 * b > 0.5; // las letras son claras
            }
        }
        return read(ink);
    }

    /** Lee una matriz de tinta [fila][columna]. */
    public static Optional<Result> read(boolean[][] ink) {
        int h = ink.length, w = h == 0 ? 0 : ink[0].length;
        int[] colInk = new int[w];
        int total = 0, first = -1, last = -1;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) if (ink[y][x]) colInk[x]++;
            total += colInk[x];
            if (colInk[x] > 0) {
                if (first < 0) first = x;
                last = x;
            }
        }
        if (total == 0) return Optional.empty();

        Result best = null;
        for (Font font : FONTS) {
            for (int shift = -MAX_SHIFT; shift <= MAX_SHIFT; shift++) {
                Result r = readWith(font, ink, colInk, first, last, shift, total);
                if (r != null && (best == null || r.error() < best.error())) best = r;
            }
        }
        return best != null && best.error() <= MAX_ERROR && !best.text().isBlank() ? Optional.of(best) : Optional.empty();
    }

    /** Programación dinámica sobre las columnas [first, last] con una fuente y un desplazamiento vertical. */
    private static Result readWith(Font font, boolean[][] ink, int[] colInk, int first, int last, int shift, int totalInk) {
        int n = last - first + 1;
        int[] cost = new int[n + 1];
        int[] fromX = new int[n + 1];
        Glyph[] glyphAt = new Glyph[n + 1];
        java.util.Arrays.fill(cost, Integer.MAX_VALUE);
        cost[0] = 0;
        for (int i = 0; i < n; i++) {
            if (cost[i] == Integer.MAX_VALUE) continue;
            int x = first + i;
            // saltar una columna (gratis si está vacía)
            int skip = cost[i] + colInk[x] * SKIP_INK_COST;
            if (skip < cost[i + 1]) {
                cost[i + 1] = skip;
                fromX[i + 1] = i;
                glyphAt[i + 1] = null;
            }
            if (colInk[x] == 0) continue; // una letra empieza en una columna con tinta
            for (Glyph g : font.glyphs()) {
                if (i + g.width() > n) continue;
                int c = cost[i] + mismatches(g, ink, x, shift);
                if (c < cost[i + g.width()]) { // "<": a igual costo gana la primera (letras antes que números)
                    cost[i + g.width()] = c;
                    fromX[i + g.width()] = i;
                    glyphAt[i + g.width()] = g;
                }
            }
        }
        if (cost[n] == Integer.MAX_VALUE) return null;

        // Reconstruir de atrás hacia adelante
        List<int[]> spans = new ArrayList<>();
        List<Glyph> glyphs = new ArrayList<>();
        for (int i = n; i > 0; i = fromX[i]) {
            if (glyphAt[i] != null) {
                glyphs.add(0, glyphAt[i]);
                spans.add(0, new int[]{fromX[i], i - 1});
            }
        }
        StringBuilder text = new StringBuilder();
        for (int k = 0; k < glyphs.size(); k++) {
            if (k > 0 && spans.get(k)[0] - spans.get(k - 1)[1] - 1 >= SPACE_GAP) text.append(' ');
            text.append(glyphs.get(k).symbol());
        }
        String s = font.lowercase() ? text.toString() : text.toString().toUpperCase();
        return new Result(s, font.name(), cost[n] / (double) totalInk);
    }

    private static int mismatches(Glyph g, boolean[][] ink, int x, int shift) {
        int h = ink.length, bad = 0;
        for (int r = 0; r < ROWS; r++) {
            int y = r + shift;
            boolean[] row = y >= 0 && y < h ? ink[y] : null;
            for (int c = 0; c < g.width(); c++) {
                boolean s = row != null && row[x + c];
                if (s != g.ink()[r][c]) bad++;
            }
        }
        // tinta del sprite fuera de las 15 filas de la plantilla (desplazada) en estas columnas
        for (int y = 0; y < h; y++) {
            int r = y - shift;
            if (r >= 0 && r < ROWS) continue;
            for (int c = 0; c < g.width(); c++) if (ink[y][x + c]) bad++;
        }
        return bad;
    }

    private static List<Font> loadFonts() {
        List<Font> fonts = new ArrayList<>();
        try (InputStream in = NameSpriteReader.class.getResourceAsStream("/fonts/vb_name_fonts.txt")) {
            if (in == null) {
                System.out.println("[NOMBRE] No se encontró fonts/vb_name_fonts.txt: el lector de nombres queda apagado.");
                return fonts;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            Font current = null;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("FONT ")) {
                    String[] p = line.split(" ");
                    current = new Font(p[1], "si".equals(p[2]), new ArrayList<>());
                    fonts.add(current);
                } else if (line.startsWith("GLYPH ") && current != null) {
                    String[] p = line.split(" ");
                    int w = Integer.parseInt(p[2]);
                    boolean[][] ink = new boolean[ROWS][w];
                    for (int y = 0; y < ROWS; y++) {
                        String row = r.readLine();
                        for (int x = 0; x < w; x++) ink[y][x] = row.charAt(x) == '#';
                    }
                    if (!"SPACE".equals(p[1])) current.glyphs().add(new Glyph(p[1], w, ink));
                }
            }
        } catch (Exception e) {
            System.out.println("[NOMBRE] No se pudieron leer las fuentes: " + e.getMessage());
        }
        return fonts;
    }
}
