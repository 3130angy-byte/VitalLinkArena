package org.example.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Muestra el sprite NAME real (imagen, nunca texto) en su tamaño nativo,
 * con el mismo mecanismo de scroll por viewport que usa DIM-Modifier
 * (NameUpdater): pausa al inicio, se desplaza, pausa al final, reinicia.
 */
public class NameSpriteBanner extends StackPane {

    private static final double SCROLL_PIXELS_PER_SECOND = 30;
    private static final double PAUSE_SECONDS = 0.8;

    private enum Phase { PAUSE_START, SCROLLING, PAUSE_END }

    private AnimationTimer scrollTimer;

    public NameSpriteBanner(Image nativeNameImage, double displayWidth, double displayHeight) {
        Rectangle background = new Rectangle(displayWidth, displayHeight, Color.BLACK);

        double nativeWidth = nativeNameImage.getWidth();
        double nativeHeight = nativeNameImage.getHeight();
        double scale = displayHeight / nativeHeight;

        ImageView imageView = new ImageView(nativeNameImage);
        imageView.setSmooth(false);
        imageView.setFitHeight(displayHeight);
        imageView.setPreserveRatio(true);

        double viewportWidthNative = Math.min(nativeWidth, displayWidth / scale);
        imageView.setViewport(new Rectangle2D(0, 0, viewportWidthNative, nativeHeight));

        setPrefSize(displayWidth, displayHeight);
        setMaxSize(displayWidth, displayHeight);
        setClip(new Rectangle(displayWidth, displayHeight));
        getChildren().addAll(background, imageView);

        double scaledFullWidth = nativeWidth * scale;
        if (scaledFullWidth > displayWidth) {
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
                        if (offset >= maxOffset) {
                            offset = maxOffset;
                            phase = Phase.PAUSE_END;
                            phaseElapsed = 0;
                        }
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