package org.example.behavior;

import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.example.animation.VPetAnimations;
import org.example.animation.VPetStageTier;
import org.example.interaction.VPetClickToMoveController;

import java.util.Random;

/**
 * Comportamiento del Digimon en el escritorio: pasear solo según el modo de
 * movimiento y reaccionar a las batallas. Reemplaza a BabyVPetController
 * sin nada de crianza (hambre, suciedad, malestar, entrenamiento) --
 * depuración de 0.0.3.
 */
public class CompanionController {

    private static final double WANDER_MIN_SECONDS = 8;
    private static final double WANDER_MAX_SECONDS = 20;
    private static final double RUN_DISTANCE_THRESHOLD = 250;

    private final Stage stage;
    private final VPetMovementController movementController;
    private final VPetClickToMoveController clickToMove;
    private final VPetStageTier stageTier;
    private final Random random = new Random();

    private boolean active = false;
    private boolean menuOpen = false;
    private PauseTransition wanderTimer;
    private VPetMovementMode mode = VPetMovementMode.BARRA;

    public CompanionController(Stage stage, VPetMovementController movementController,
                               VPetClickToMoveController clickToMove, VPetStageTier stageTier) {
        this.stage = stage;
        this.movementController = movementController;
        this.clickToMove = clickToMove;
        this.stageTier = stageTier;
    }

    public void start() {
        if (active) return;
        active = true;
        scheduleNextWander();
    }

    public void stop() {
        active = false;
        if (wanderTimer != null) wanderTimer.stop();
    }

    public void setMenuOpen(boolean menuOpen) { this.menuOpen = menuOpen; }
    public boolean isMenuOpen() { return menuOpen; }

    public void setMode(VPetMovementMode mode) { this.mode = mode; }

    public VPetMovementMode getMode() { return mode; }

    public void victoryAction() {
        movementController.playAction(VPetAnimations.celebrate(stageTier), 3.0, null);
    }

    public void loseAction() {
        movementController.playAction(VPetAnimations.lose(stageTier), 3.0, null);
    }

    private void scheduleNextWander() {
        if (!active) return;
        double seconds = WANDER_MIN_SECONDS + random.nextDouble() * (WANDER_MAX_SECONDS - WANDER_MIN_SECONDS);
        wanderTimer = new PauseTransition(Duration.seconds(seconds));
        wanderTimer.setOnFinished(e -> { wanderIfPossible(); scheduleNextWander(); });
        wanderTimer.play();
    }

    private void wanderIfPossible() {
        if (menuOpen || mode == VPetMovementMode.QUIETO || movementController.isMoving() || clickToMove.isSelected()) {
            return;
        }

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double frameW = movementController.getFrameWidth();
        double frameH = movementController.getFrameHeight();
        double targetX = bounds.getMinX() + random.nextDouble() * (bounds.getMaxX() - frameW - bounds.getMinX());
        double targetY = (mode == VPetMovementMode.ABSOLUTO_LIBRE)
                ? bounds.getMinY() + random.nextDouble() * (bounds.getMaxY() - frameH - bounds.getMinY())
                : bounds.getMaxY() - frameH;

        double distance = Math.hypot(targetX - stage.getX(), targetY - stage.getY());
        if (distance > RUN_DISTANCE_THRESHOLD) {
            movementController.runTo(targetX, targetY);
        } else {
            movementController.walkTo(targetX, targetY);
        }
    }
}
