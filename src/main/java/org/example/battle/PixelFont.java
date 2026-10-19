package org.example.battle;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.HashMap;
import java.util.Map;

/**
 * Letras de pixel art para el 1 vs 1 (se dibujan con rectángulos en la cuadrícula de la escena, así
 * quedan nítidas al agrandar). Dos tamaños: 5x7 (textos grandes y la ronda) y 3x5 (el atributo en los
 * marcadores). Solo mayúsculas, números y lo que usan los textos de la pelea.
 */
final class PixelFont {

    private PixelFont() {}

    static final int BIG_W = 5, BIG_H = 7, SMALL_W = 3, SMALL_H = 5;

    private static final Map<Character, String[]> BIG = new HashMap<>();
    private static final Map<Character, String[]> SMALL = new HashMap<>();

    static {
        big('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        big('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
        big('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
        big('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.");
        big('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
        big('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....");
        big('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####");
        big('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
        big('I', ".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###.");
        big('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..");
        big('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
        big('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
        big('M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#");
        big('N', "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#");
        big('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        big('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
        big('Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#");
        big('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
        big('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
        big('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
        big('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
        big('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
        big('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#");
        big('X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#");
        big('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..");
        big('Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####");
        big('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.");
        big('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
        big('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
        big('3', "####.", "....#", "....#", ".###.", "....#", "....#", "####.");
        big('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
        big('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
        big('6', ".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.");
        big('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
        big('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
        big('9', ".###.", "#...#", "#...#", ".####", "....#", "....#", ".###.");
        big('!', "..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#..");
        big('¡', "..#..", ".....", "..#..", "..#..", "..#..", "..#..", "..#..");
        big('.', ".....", ".....", ".....", ".....", ".....", ".....", "..#..");
        big('-', ".....", ".....", ".....", ".###.", ".....", ".....", ".....");
        big(' ', ".....", ".....", ".....", ".....", ".....", ".....", ".....");

        small('A', ".#.", "#.#", "###", "#.#", "#.#");
        small('C', ".##", "#..", "#..", "#..", ".##");
        small('D', "##.", "#.#", "#.#", "#.#", "##.");
        small('E', "###", "#..", "##.", "#..", "###");
        small('F', "###", "#..", "##.", "#..", "#..");
        small('I', "###", ".#.", ".#.", ".#.", "###");
        small('L', "#..", "#..", "#..", "#..", "###");
        small('N', "#.#", "###", "###", "###", "#.#");
        small('R', "##.", "#.#", "##.", "#.#", "#.#");
        small('S', ".##", "#..", ".#.", "..#", "##.");
        small('T', "###", ".#.", ".#.", ".#.", ".#.");
        small('U', "#.#", "#.#", "#.#", "#.#", "###");
        small('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        small('-', "...", "...", "###", "...", "...");
        small(' ', "...", "...", "...", "...", "...");
    }

    private static void big(char c, String... rows) { BIG.put(c, rows); }
    private static void small(char c, String... rows) { SMALL.put(c, rows); }

    /** Ancho en píxeles de la escena (letras de 5 + 1 de espacio, por la escala). */
    static int width(String text, int scale) {
        return text.isEmpty() ? 0 : (text.length() * (BIG_W + 1) - 1) * scale;
    }

    static int smallWidth(String text) {
        return text.isEmpty() ? 0 : text.length() * (SMALL_W + 1) - 1;
    }

    /** Texto 5x7 con su esquina arriba-izquierda en (x, y); con borde oscuro de 1 píxel si outline != null. */
    static void draw(GraphicsContext g, String text, int x, int y, int scale, Color color, Color outline) {
        if (outline != null) {
            int[][] around = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
            for (int[] d : around) paint(g, BIG, BIG_W, text, x + d[0], y + d[1], scale, outline);
        }
        paint(g, BIG, BIG_W, text, x, y, scale, color);
    }

    static void drawCentered(GraphicsContext g, String text, int centerX, int y, int scale, Color color, Color outline) {
        draw(g, text, centerX - width(text, scale) / 2, y, scale, color, outline);
    }

    /** Texto 3x5 (sin escala). */
    static void drawSmall(GraphicsContext g, String text, int x, int y, Color color) {
        paint(g, SMALL, SMALL_W, text, x, y, 1, color);
    }

    private static void paint(GraphicsContext g, Map<Character, String[]> font, int glyphW, String text,
                              int x, int y, int scale, Color color) {
        g.setFill(color);
        for (int i = 0; i < text.length(); i++) {
            String[] rows = font.get(Character.toUpperCase(text.charAt(i)));
            if (rows == null) continue;
            int gx = x + i * (glyphW + 1) * scale;
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < glyphW; c++) {
                    if (rows[r].charAt(c) == '#') g.fillRect(gx + c * scale, y + r * scale, scale, scale);
                }
            }
        }
    }
}
