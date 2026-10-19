package org.example.animation;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Agrandado en PIXEL ART de verdad (2026-10-18, premio del campeón de un torneo): cada píxel del sprite pasa a ser
 * un bloque de k x k del mismo color, como el agrandado entero de la Aventura.
 *
 * Por qué hace falta: medido en esta laptop, un ImageView con {@code setSmooth(false)} igual SUAVIZA al agrandar (un
 * damero de 2 colores agrandado x4 sale con 12 colores). Los lienzos con {@code setImageSmoothing(false)} (sala,
 * coliseo) sí duplican bien; los ImageView (escritorio, ARENA, portal) no. Con {@link #crisp} el ImageView recibe
 * una imagen YA agrandada a su tamaño: no tiene que escalar nada y queda nítido en cualquier equipo.
 */
public final class PixelScale {

    private static final String ORIGINAL = "pixelScale.original";
    /** Agrandados ya hechos (cada sprite se agranda una sola vez por tamaño). */
    private static final Map<Image, Map<Integer, Image>> CACHE = new WeakHashMap<>();
    /** Imágenes hechas aquí (para no tomarlas como si fueran un sprite nuevo). */
    private static final Map<Image, Boolean> MADE_HERE = new WeakHashMap<>();

    private PixelScale() {}

    /** La imagen agrandada k veces duplicando píxeles (k = 1: la misma). */
    public static Image scaled(Image src, int k) {
        if (src == null || k <= 1 || src.getPixelReader() == null) return src;
        Map<Integer, Image> bySize = CACHE.computeIfAbsent(src, s -> new java.util.HashMap<>());
        Image done = bySize.get(k);
        if (done != null) return done;
        int w = (int) src.getWidth(), h = (int) src.getHeight();
        WritableImage out = new WritableImage(w * k, h * k);
        PixelReader in = src.getPixelReader();
        PixelWriter px = out.getPixelWriter();
        int[] row = new int[w * k];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = in.getArgb(x, y);
                for (int i = 0; i < k; i++) row[x * k + i] = argb;
            }
            for (int i = 0; i < k; i++) {
                px.setPixels(0, y * k + i, w * k, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), row, 0, w * k);
            }
        }
        bySize.put(k, out);
        MADE_HERE.put(out, Boolean.TRUE);
        return out;
    }

    /**
     * Desde ahora, la imagen de {@code view} se agranda duplicando píxeles hasta su tamaño (fitWidth) cuando es un
     * múltiplo entero del sprite; si no, queda tal cual. Sigue funcionando cuando cambian la imagen (animaciones) o
     * el tamaño (el premio se prende y se apaga).
     */
    public static void crisp(ImageView view) {
        view.imageProperty().addListener((obs, old, img) -> {
            if (img == null) {
                view.getProperties().remove(ORIGINAL);
                return;
            }
            if (!MADE_HERE.containsKey(img)) view.getProperties().put(ORIGINAL, img);
            refresh(view);
        });
        view.fitWidthProperty().addListener((obs, old, w) -> refresh(view));
        view.fitHeightProperty().addListener((obs, old, h) -> refresh(view));
        if (view.getImage() != null) {
            view.getProperties().put(ORIGINAL, view.getImage());
            refresh(view);
        }
    }

    private static void refresh(ImageView view) {
        Object o = view.getProperties().get(ORIGINAL);
        if (!(o instanceof Image original) || original.getWidth() <= 0) return;
        double kw = view.getFitWidth() / original.getWidth(), kh = view.getFitHeight() / original.getHeight();
        int k = (int) Math.round(kw);
        boolean whole = k >= 2 && Math.abs(kw - k) < 0.01 && Math.abs(kh - k) < 0.01;
        Image want = whole ? scaled(original, k) : original;
        if (view.getImage() != want) view.setImage(want);
    }
}
