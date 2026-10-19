package org.example.animation;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.Blend;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.ColorInput;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fase 3 del plan del LAB, opción A (decisión del usuario, 2026-10-15): una COPIA en el escritorio se ve
 * teñida de celeste y con píxeles celestes animados a su alrededor, que suben y se apagan. Así nunca se
 * confunde con un Digimon vivo. (La opción B, un recuadro de holograma con líneas, quedó para después.)
 *
 * El tinte va en la imagen del Digimon (sirve con todas sus animaciones y portales); los píxeles, en un
 * lienzo encima que no recibe clics. Se dibujan a saltos (cada 0,1 s), como el pixel art del resto.
 */
public final class GhostLook {

    public static final Color CYAN = Color.web("#5fe6ff");
    /** Píxeles de la ventana del Digimon por cada píxel del sprite (64x56 para un sprite de 32x28). */
    private static final int PIXEL = 2;
    private static final int MAX_SPARKS = 9;

    private record Spark(double x, double y, int life, int maxLife) {}

    private final Canvas canvas;
    private final List<Spark> sparks = new ArrayList<>();
    private final Random random = new Random();
    private final AnimationTimer timer;
    private long lastStep;

    /** Tamaño de cada píxel celeste: el de los píxeles del sprite (2, o 4 con el premio de torneo al doble). */
    private final int pixel;

    private GhostLook(Canvas canvas) {
        this.canvas = canvas;
        this.pixel = PIXEL * Math.max(1, (int) Math.round(canvas.getWidth() / 64));
        this.timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (now - lastStep < 100_000_000L) return;
                lastStep = now;
                step();
            }
        };
    }

    /** Tiñe la imagen y agrega los píxeles animados encima (en {@code root}, que contiene la imagen). */
    public static GhostLook apply(ImageView image, Pane root) {
        ColorInput tint = new ColorInput(0, 0, image.getFitWidth(), image.getFitHeight(), CYAN.deriveColor(0, 1, 1, 0.6));
        // Primero en grises (si no, el naranja de un Agumon quedaba verde) y encima el celeste. SRC_ATOP: el color
        // solo cae donde hay sprite (el fondo transparente sigue transparente). La opacidad no: los portales la usan.
        javafx.scene.effect.ColorAdjust gray = new javafx.scene.effect.ColorAdjust(0, -1, 0.15, 0);
        image.setEffect(new Blend(BlendMode.SRC_ATOP, gray, tint));
        Canvas canvas = new Canvas(image.getFitWidth(), image.getFitHeight());
        canvas.setMouseTransparent(true);
        root.getChildren().add(canvas);
        GhostLook look = new GhostLook(canvas);
        look.timer.start();
        return look;
    }

    /**
     * Fase 4 (sala online): el mismo celeste, pero horneado en una imagen nueva (grises + 60 % de celeste, la
     * transparencia intacta), para dibujar la copia en un lienzo (sala, coliseo, ARENA) donde no hay efectos.
     */
    public static javafx.scene.image.Image tint(javafx.scene.image.Image src) {
        int w = (int) src.getWidth(), h = (int) src.getHeight();
        if (w <= 0 || h <= 0 || src.getPixelReader() == null) return src;
        javafx.scene.image.PixelReader in = src.getPixelReader();
        javafx.scene.image.WritableImage out = new javafx.scene.image.WritableImage(w, h);
        javafx.scene.image.PixelWriter px = out.getPixelWriter();
        double cr = CYAN.getRed() * 255, cg = CYAN.getGreen() * 255, cb = CYAN.getBlue() * 255;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = in.getArgb(x, y);
                int a = argb >>> 24;
                if (a == 0) {
                    px.setArgb(x, y, 0);
                    continue;
                }
                double gray = Math.min(255, 0.299 * (argb >> 16 & 255) + 0.587 * (argb >> 8 & 255) + 0.114 * (argb & 255) + 38);
                int r = (int) Math.round(gray * 0.4 + cr * 0.6);
                int g = (int) Math.round(gray * 0.4 + cg * 0.6);
                int b = (int) Math.round(gray * 0.4 + cb * 0.6);
                px.setArgb(x, y, a << 24 | r << 16 | g << 8 | b);
            }
        }
        return out;
    }

    public void stop() {
        timer.stop();
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    /** Lo quita del todo (para volver a ponerlo a otro tamaño: el premio de torneo al doble se prende y se apaga). */
    public void dispose(ImageView image) {
        stop();
        image.setEffect(null);
        if (canvas.getParent() instanceof Pane parent) parent.getChildren().remove(canvas);
    }

    private void step() {
        List<Spark> next = new ArrayList<>();
        for (Spark s : sparks) {
            if (s.life() > 1) next.add(new Spark(s.x(), s.y() - pixel, s.life() - 1, s.maxLife())); // sube un píxel
        }
        if (next.size() < MAX_SPARKS && random.nextInt(3) > 0) {
            // Nacen en los bordes de la figura (abajo y a los costados) y suben.
            int cols = (int) (canvas.getWidth() / pixel), rows = (int) (canvas.getHeight() / pixel);
            int col = random.nextBoolean() ? random.nextInt(5) : cols - 1 - random.nextInt(5);
            if (random.nextInt(3) == 0) col = random.nextInt(cols);
            int row = rows / 3 + random.nextInt(rows - rows / 3);
            int life = 4 + random.nextInt(6);
            next.add(new Spark(col * pixel, row * pixel, life, life));
        }
        sparks.clear();
        sparks.addAll(next);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        for (Spark s : sparks) {
            double alpha = Math.min(1.0, 0.25 + (double) s.life() / s.maxLife());
            g.setFill(CYAN.deriveColor(0, 1, 1, alpha));
            g.fillRect(s.x(), s.y(), pixel, pixel);
        }
    }
}
