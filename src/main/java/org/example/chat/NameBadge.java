package org.example.chat;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.image.Image;

import java.util.ArrayList;
import java.util.List;

/**
 * Insignia del nombre dentro de un texto: fondo negro, texto blanco en
 * negrita. Si el nombre es más ancho que el espacio asignado, se desplaza
 * hacia la izquierda y reaparece por la derecha, en vez de recortarse.
 * Usa Text (no Label) para medir, porque Text calcula su tamaño real de
 * inmediato a partir de la fuente puesta por código — no necesita que el
 * nodo ya esté en una escena ni un pase de CSS, a diferencia de un Label
 * con negrita puesta por CSS.
 */
public class NameBadge extends StackPane {

    private static final double MAX_WIDTH = 90;
    private static final double SCROLL_SPEED = 25; // px/seg
    private static final double PAUSE_SECONDS = 0.6;
    private static final double PAD_H = 6;
    private static final double PAD_V = 1;
    private static final Font BADGE_FONT = Font.font(null, FontWeight.BOLD, 12);

    private final Text textNode;
    private AnimationTimer scrollTimer;

    public NameBadge(String name) {
        textNode = new Text(name);
        textNode.setFill(Color.WHITE);
        textNode.setFont(BADGE_FONT);

        double textWidth = textNode.getLayoutBounds().getWidth();
        double textHeight = textNode.getLayoutBounds().getHeight();
        double badgeWidth = textWidth + PAD_H * 2;
        double badgeHeight = textHeight + PAD_V * 2;

        Rectangle background = new Rectangle(Math.min(badgeWidth, MAX_WIDTH), badgeHeight);
        background.setFill(Color.BLACK);

        getChildren().addAll(background, textNode);

        if (badgeWidth <= MAX_WIDTH) {
            setPrefSize(badgeWidth, badgeHeight);
            setMaxSize(badgeWidth, badgeHeight);
        } else {
            setPrefSize(MAX_WIDTH, badgeHeight);
            setMaxSize(MAX_WIDTH, badgeHeight);
            setClip(new Rectangle(MAX_WIDTH, badgeHeight));
            startScrolling(textWidth);
        }
    }

    private void startScrolling(double textWidth) {
        double travelDistance = textWidth - MAX_WIDTH + PAD_H * 2;
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
                if (offset >= travelDistance) {
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

    /** Parte un mensaje en texto normal + insignias, dondequiera que aparezca el nombre. */
    public static List<Node> buildInlineNodes(String message, String name, Color textColor, Font font) {
        List<Node> nodes = new ArrayList<>();
        if (message == null) return nodes;

        if (name == null || name.isBlank()) {
            nodes.add(plainText(message, textColor, font));
            return nodes;
        }

        int idx = 0;
        while (true) {
            int found = message.indexOf(name, idx);
            if (found < 0) {
                if (idx < message.length()) nodes.add(plainText(message.substring(idx), textColor, font));
                break;
            }
            if (found > idx) nodes.add(plainText(message.substring(idx, found), textColor, font));
            nodes.add(new NameBadge(name));
            idx = found + name.length();
        }
        return nodes;
    }

    private static final double INLINE_SPRITE_HEIGHT = 15.0;

    /** Igual que buildInlineNodes, pero inserta el sprite real del nombre en vez de un recuadro de texto. */
    public static List<Node> buildInlineNodesWithSprite(String message, String name, Image nameSpriteImage,
                                                        Color textColor, Font font) {
        List<Node> nodes = new ArrayList<>();
        if (message == null) return nodes;

        if (name == null || name.isBlank() || nameSpriteImage == null) {
            nodes.add(plainText(message, textColor, font));
            return nodes;
        }

        int idx = 0;
        while (true) {
            int found = message.indexOf(name, idx);
            if (found < 0) {
                if (idx < message.length()) nodes.add(plainText(message.substring(idx), textColor, font));
                break;
            }
            if (found > idx) nodes.add(plainText(message.substring(idx, found), textColor, font));
            nodes.add(new InlineNameSprite(nameSpriteImage, INLINE_SPRITE_HEIGHT));
            idx = found + name.length();
        }
        return nodes;
    }

    private static Text plainText(String s, Color textColor, Font font) {
        Text t = new Text(s);
        t.setFill(textColor);
        t.setFont(font);
        return t;
    }
}