package org.example.chat;

import javafx.animation.AnimationTimer;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Insignia de nombre INLINE dentro de una oración, con el sprite NAME real
 * (imagen, nunca texto) escalado a la altura de una línea de texto, con
 * scroll si es más ancho de lo razonable -- mismo mecanismo que
 * NameSpriteBanner (panel "Digimon"), a tamaño de línea. Fondo negro fijo,
 * porque la tinta del sprite es casi blanca y se perdería sobre fondos
 * claros como la burbuja.
 */
public class InlineNameSprite extends StackPane {

    private static final double MAX_CONTENT_WIDTH = 64;
    private static final double PADDING_H = 4;
    private static final double SCROLL_PIXELS_PER_SECOND = 18;
    private static final double PAUSE_SECONDS = 0.6;

    private enum Phase { PAUSE_START, SCROLLING, PAUSE_END }

    private AnimationTimer scrollTimer;

    public InlineNameSprite(Image nativeNameImage, double lineHeight) {
        double nativeWidth = nativeNameImage.getWidth();
        double nativeHeight = nativeNameImage.getHeight();
        double scale = lineHeight / nativeHeight;

        double contentWidth = Math.min(MAX_CONTENT_WIDTH, nativeWidth * scale);
        double totalWidth = contentWidth + PADDING_H * 2;
        double totalHeight = lineHeight + 2;

        Rectangle background = new Rectangle(totalWidth, totalHeight, Color.BLACK);
        background.setArcWidth(4);
        background.setArcHeight(4);

        ImageView imageView = new ImageView(nativeNameImage);
        imageView.setSmooth(false);
        imageView.setFitHeight(lineHeight);
        imageView.setPreserveRatio(true);
        imageView.setLayoutX(PADDING_H);
        imageView.setLayoutY(1);

        double viewportWidthNative = Math.min(nativeWidth, contentWidth / scale);
        imageView.setViewport(new Rectangle2D(0, 0, viewportWidthNative, nativeHeight));

        setPrefSize(totalWidth, totalHeight);
        setMaxSize(totalWidth, totalHeight);
        getChildren().addAll(background, imageView);

        if (nativeWidth * scale > MAX_CONTENT_WIDTH) {
            startScrolling(imageView, nativeHeight, viewportWidthNative, nativeWidth - viewportWidthNative, scale);
        }
    }

    private void startScrolling(ImageView imageView, double nativeHeight, double viewportWidthNative,
                                double maxOffset, double scale) {
        scrollTimer = new AnimationTimer() {
            private long lastNow = -1;
            private double offset = 0;
            private Phase phase = Phase.PAUSE_START;
            private double phaseElapsed = 0;

            @Override
            public void handle(long now) {
                if (lastNow < 0) { lastNow = now; return; }
                double dt = (now - lastNow) / 1_000_000_000.0;
                lastNow = now;
                phaseElapsed += dt;

                switch (phase) {
                    case PAUSE_START -> {
                        offset = 0;
                        if (phaseElapsed >= PAUSE_SECONDS) { phase = Phase.SCROLLING; phaseElapsed = 0; }
                    }
                    case SCROLLING -> {
                        offset += (SCROLL_PIXELS_PER_SECOND / scale) * dt;
                        if (offset >= maxOffset) { offset = maxOffset; phase = Phase.PAUSE_END; phaseElapsed = 0; }
                    }
                    case PAUSE_END -> {
                        offset = maxOffset;
                        if (phaseElapsed >= PAUSE_SECONDS) { phase = Phase.PAUSE_START; phaseElapsed = 0; }
                    }
                }

                imageView.setViewport(new Rectangle2D(offset, 0, viewportWidthNative, nativeHeight));
            }
        };
        scrollTimer.start();
    }

    public void stop() {
        if (scrollTimer != null) scrollTimer.stop();
    }
}