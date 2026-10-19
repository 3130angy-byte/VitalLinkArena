package org.example.dim;

import com.github.cfogrady.vb.dim.sprite.SpriteData;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/**
 * Convierte SpriteData.Sprite (RGB565 crudo de la DIM) a Image de JavaFX.
 * toImage(...) centra en un lienzo fijo (comportamiento original, sin
 * cambios). toNativeImage(...) es nuevo: devuelve el sprite en su tamaño
 * REAL, sin forzarlo a ningún lienzo -- necesario para NAME, que puede ser
 * más ancho que 64px (confirmado con DIM-Modifier: múltiplos de 80px de
 * ancho, 15px de alto). Ambos métodos comparten la misma decodificación de
 * píxeles ya probada en producción.
 */
public final class DimSpriteImageFactory {

    private DimSpriteImageFactory() {}

    public static Image toImage(SpriteData.Sprite sprite, int canvasWidth, int canvasHeight) {
        int width = sprite.getWidth();
        int height = sprite.getHeight();

        WritableImage canvas = new WritableImage(canvasWidth, canvasHeight);
        PixelWriter writer = canvas.getPixelWriter();

        for (int y = 0; y < canvasHeight; y++) {
            for (int x = 0; x < canvasWidth; x++) {
                writer.setArgb(x, y, 0x00000000);
            }
        }

        int offsetX = (canvasWidth - width) / 2;
        int offsetY = canvasHeight - height;

        decodeInto(sprite, writer, offsetX, offsetY, canvasWidth, canvasHeight);

        return canvas;
    }

    /**
     * Extrae el sprite en su tamaño NATIVO real (sin centrar ni recortar en
     * ningún lienzo fijo). Es lo que corrige el recorte de nombres largos:
     * antes forzábamos NAME dentro de 64x56 como cualquier otro sprite, y
     * eso era lo que cortaba el inicio del nombre, no un problema del
     * sprite en sí.
     */
    public static Image toNativeImage(SpriteData.Sprite sprite) {
        int width = sprite.getWidth();
        int height = sprite.getHeight();

        WritableImage canvas = new WritableImage(width, height);
        PixelWriter writer = canvas.getPixelWriter();

        decodeInto(sprite, writer, 0, 0, width, height);

        return canvas;
    }

    private static void decodeInto(SpriteData.Sprite sprite, PixelWriter writer, int offsetX, int offsetY,
                                   int targetWidth, int targetHeight) {
        int width = sprite.getWidth();
        int height = sprite.getHeight();
        byte[] pixelData = sprite.getPixelData();

        int expectedBytes = width * height * 2;
        if (pixelData.length < expectedBytes) {
            throw new IllegalArgumentException(
                    "pixelData tiene " + pixelData.length + " bytes, se esperaban " + expectedBytes
                            + " para " + width + "x" + height + " en RGB565.");
        }

        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int low = pixelData[i] & 0xFF;
                int high = pixelData[i + 1] & 0xFF;
                int pixel565 = (high << 8) | low;
                i += 2;

                int r5 = (pixel565 >> 11) & 0x1F;
                int g6 = (pixel565 >> 5) & 0x3F;
                int b5 = pixel565 & 0x1F;

                int r8 = (r5 * 255) / 31;
                int g8 = (g6 * 255) / 63;
                int b8 = (b5 * 255) / 31;

                int canvasX = offsetX + x;
                int canvasY = offsetY + y;
                if (canvasX < 0 || canvasX >= targetWidth || canvasY < 0 || canvasY >= targetHeight) {
                    continue;
                }

                if (isGreen(r8, g8, b8)) {
                    writer.setArgb(canvasX, canvasY, 0x00000000);
                } else {
                    int argb = (0xFF << 24) | (r8 << 16) | (g8 << 8) | b8;
                    writer.setArgb(canvasX, canvasY, argb);
                }
            }
        }
    }

    private static boolean isGreen(int r, int g, int b) {
        return g > 217 && r < 64 && b < 64;
    }
}