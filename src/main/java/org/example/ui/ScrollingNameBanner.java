package org.example.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Barra negra con el nombre a todo el ancho del panel, con desplazamiento
 * si es más largo de lo que cabe — imita la franja de nombre del
 * dispositivo real. Variante de banner dedicada al panel "Digimon"; no
 * toca NameBadge (que sigue usándose tal cual en el globo).
 */
public class ScrollingNameBanner extends StackPane {

    private static final double SCROLL_SPEED = 22;
    private static final double PAUSE_SECONDS = 0.7;

    private final Text textNode;
    private AnimationTimer scrollTimer;

    public ScrollingNameBanner(String name, double width, double height) {
        Rectangle bg = new Rectangle(width, height, Color.BLACK);

        textNode = new Text(name.toUpperCase());
        textNode.setFill(Color.WHITE);
        textNode.setFont(Font.font("Consolas", FontWeight.BOLD, 14));

        setPrefSize(width, height);
        setMaxSize(width, height);
        setClip(new Rectangle(width, height));
        getChildren().addAll(bg, textNode);

        double textWidth = textNode.getLayoutBounds().getWidth();
        if (textWidth > width - 10) {
            startScrolling(textWidth, width);
        }
    }

    private void startScrolling(double textWidth, double panelWidth) {
        double travel = textWidth - panelWidth + 16;
        scrollTimer = new AnimationTimer() {
            private long lastNow = -1;
            private double elapsed = 0;
            private boolean pausing = true;
            private double pauseElapsed = 0;

            @Override
            public void handle(long now) {
                if (lastNow < 0) { lastNow = now; return; }
                double dt = (now - lastNow) / 1_000_000_000.0;
                lastNow = now;

                if (pausing) {
                    pauseElapsed += dt;
                    if (pauseElapsed >= PAUSE_SECONDS) { pausing = false; pauseElapsed = 0; }
                    return;
                }

                elapsed += dt;
                double offset = elapsed * SCROLL_SPEED;
                if (offset >= travel) {
                    textNode.setTranslateX(0);
                    elapsed = 0;
                    pausing = true;
                } else {
                    textNode.setTranslateX(-offset);
                }
            }
        };
        scrollTimer.start();
    }

    public void stop() {
        if (scrollTimer != null) scrollTimer.stop();
    }
}