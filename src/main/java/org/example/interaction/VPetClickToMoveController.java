package org.example.interaction;

import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import org.example.behavior.VPetMovementController;

/**
 * Clic para mover manualmente — aplica desde Baby I hasta la etapa que
 * corresponda (nunca en huevo). Mismo patrón ya confirmado estable con
 * Gammamon: overlay TRANSPARENT creado una sola vez y reutilizado con
 * show()/hide(), con revelado diferido de opacidad en el primer show.
 */
public class VPetClickToMoveController {

    public static final double RUN_DISTANCE_THRESHOLD = 250; // px

    private final Stage petStage;
    private final VPetMovementController movementController;

    private Stage overlayStage;
    private Rectangle2D overlayBounds;
    private boolean overlayEverShown = false;
    private boolean selected = false;

    public VPetClickToMoveController(Stage petStage, VPetMovementController movementController) {
        this.petStage = petStage;
        this.movementController = movementController;
    }

    public void onPetClicked() {
        if (selected) {
            deselect();
        } else {
            select();
        }
    }

    /** Suelta la selección sin mover (p. ej. al empezar la animación del portal). */
    public void cancel() {
        if (selected) deselect();
    }

    private void select() {
        selected = true;
        showOverlay();
    }

    private void deselect() {
        selected = false;
        hideOverlay();
    }

    private void ensureOverlayCreated() {
        if (overlayStage != null) return;

        overlayBounds = Screen.getPrimary().getBounds();

        Pane overlayRoot = new Pane();
        overlayRoot.setBackground(Background.EMPTY);

        Scene overlayScene = new Scene(overlayRoot, overlayBounds.getWidth(), overlayBounds.getHeight());
        overlayScene.setFill(Color.rgb(0, 0, 0, 0.004));

        overlayScene.setOnMouseClicked(e -> {
            double frameW = movementController.getFrameWidth();
            double frameH = movementController.getFrameHeight();

            double rawTargetX = e.getScreenX() - frameW / 2;
            double rawTargetY = e.getScreenY() - frameH / 2;

            double minX = overlayBounds.getMinX();
            double maxX = overlayBounds.getMaxX() - frameW;
            double minY = overlayBounds.getMinY();
            double maxY = overlayBounds.getMaxY() - frameH;

            double targetX = Math.max(minX, Math.min(maxX, rawTargetX));
            double targetY = Math.max(minY, Math.min(maxY, rawTargetY));

            deselect();

            double distance = Math.hypot(targetX - petStage.getX(), targetY - petStage.getY());
            if (distance > RUN_DISTANCE_THRESHOLD) {
                movementController.runTo(targetX, targetY);
            } else {
                movementController.walkTo(targetX, targetY);
            }
        });

        overlayStage = new Stage();
        overlayStage.initOwner(petStage);
        overlayStage.initStyle(StageStyle.TRANSPARENT);
        overlayStage.setScene(overlayScene);
        overlayStage.setX(overlayBounds.getMinX());
        overlayStage.setY(overlayBounds.getMinY());
        overlayStage.setAlwaysOnTop(true);
    }

    private void showOverlay() {
        ensureOverlayCreated();

        if (!overlayEverShown) {
            overlayEverShown = true;
            overlayStage.setOpacity(0);
            overlayStage.show();

            PauseTransition reveal = new PauseTransition(Duration.millis(120));
            reveal.setOnFinished(e -> overlayStage.setOpacity(1));
            reveal.play();
        } else {
            overlayStage.setOpacity(1);
            overlayStage.show();
        }

        overlayStage.requestFocus();
        petStage.toFront();
    }

    private void hideOverlay() {
        if (overlayStage != null) {
            overlayStage.hide();
        }
    }

    public boolean isSelected() {
        return selected;
    }
}