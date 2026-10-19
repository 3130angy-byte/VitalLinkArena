package org.example.battle;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Ventana/escena interna de las batallas (Batalla aleatoria, y el VS online
 * a futuro) — da la sensación de "portal digital", nunca un navegador real.
 */
public class PortalTransitionScene {

    private static final double FADE_SECONDS = 0.4;

    private final Stage portalStage;
    private final StackPane contentHolder;

    /** Tamaño como fracción de la pantalla. */
    public PortalTransitionScene(double widthFraction, double heightFraction) {
        this(widthFraction, heightFraction, false);
    }

    /**
     * asPixels=true: width/height son píxeles REALES, no fracciones de
     * pantalla -- para cuando el tamaño de ventana debe coincidir
     * exactamente con la proporción de un fondo externo (ej. el coliseo
     * de batalla), sin importar la proporción real del monitor del
     * usuario. Evita el desajuste que producía bordes negros.
     */
    public PortalTransitionScene(double width, double height, boolean asPixels) {
        contentHolder = new StackPane();
        contentHolder.setBackground(new Background(new BackgroundFill(
                Color.rgb(5, 15, 10), CornerRadii.EMPTY, null)));
        contentHolder.setStyle("-fx-border-color: #33ff55; -fx-border-width: 2;");

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double w = asPixels ? width : bounds.getWidth() * width;
        double h = asPixels ? height : bounds.getHeight() * height;

        Scene scene = new Scene(contentHolder, w, h);
        scene.setFill(Color.rgb(5, 15, 10));

        portalStage = new Stage();
        portalStage.initStyle(StageStyle.UNDECORATED);
        portalStage.setAlwaysOnTop(true);
        portalStage.setScene(scene);
        portalStage.setX(bounds.getMinX() + (bounds.getWidth() - w) / 2);
        portalStage.setY(bounds.getMinY() + (bounds.getHeight() - h) / 2);
    }

    public void setContent(Node node) {
        contentHolder.getChildren().setAll(node);
    }

    public Window getWindow() {
        return portalStage;
    }

    public void open(Runnable onOpened) {
        portalStage.setOpacity(0);
        portalStage.show();
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(portalStage.opacityProperty(), 0)),
                new KeyFrame(Duration.seconds(FADE_SECONDS), new KeyValue(portalStage.opacityProperty(), 1))
        );
        t.setOnFinished(e -> { if (onOpened != null) onOpened.run(); });
        t.play();
    }

    public void close(Runnable onClosed) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.seconds(FADE_SECONDS), new KeyValue(portalStage.opacityProperty(), 0))
        );
        t.setOnFinished(e -> {
            portalStage.hide();
            if (onClosed != null) onClosed.run();
        });
        t.play();
    }
}