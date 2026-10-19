package org.example.behavior;

import javafx.animation.AnimationTimer;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.ImageView;
import javafx.stage.Screen;
import javafx.stage.Stage;

import org.example.animation.DimAnimationPlayer;
import org.example.animation.SpriteRole;
import org.example.animation.VPetAnimations;
import org.example.animation.VPetStageTier;

public class VPetMovementController {

    private static final double WALK_SPEED = 80;
    private static final double WALK_FPS = 6;
    private static final double RUN_SPEED = 200;
    private static final double RUN_FPS = 10;

    private final Stage stage;
    private final ImageView imageView;
    private final DimAnimationPlayer animationPlayer;
    private VPetStageTier stageTier;

    private AnimationTimer timer;
    private boolean moving = false;
    private SpriteRole[] defaultAnimation;

    public VPetMovementController(Stage stage, ImageView imageView, DimAnimationPlayer animationPlayer, VPetStageTier stageTier) {
        this.stage = stage;
        this.imageView = imageView;
        this.animationPlayer = animationPlayer;
        this.stageTier = stageTier;
        this.defaultAnimation = VPetAnimations.idle(stageTier);
    }

    public void setStageTier(VPetStageTier stageTier) {
        this.stageTier = stageTier;
        setDefaultAnimation(VPetAnimations.idle(stageTier));
    }

    public boolean isMoving() { return moving; }

    // Misma lógica que el MovementController legacy: scaleX > 0 = mirando a la izquierda.
    public boolean isFacingLeft() { return imageView.getScaleX() > 0; }

    public double getFrameWidth() { return imageView.getFitWidth(); }
    public double getFrameHeight() { return imageView.getFitHeight(); }

    public void setDefaultAnimation(SpriteRole[] roles) {
        this.defaultAnimation = roles;
        if (!moving) {
            animationPlayer.play(defaultAnimation, 2.0);
        }
    }

    public void playAction(SpriteRole[] roles, double fps, Runnable onFinished) {
        if (timer != null) timer.stop();
        moving = true;
        animationPlayer.playOnce(roles, fps, () -> {
            moving = false;
            animationPlayer.play(defaultAnimation, 2.0);
            if (onFinished != null) onFinished.run();
        });
    }

    public void walkTo(double targetX, double targetY) {
        moveTo(targetX, targetY, WALK_SPEED, VPetAnimations.walk(stageTier), WALK_FPS);
    }

    public void runTo(double targetX, double targetY) {
        moveTo(targetX, targetY, RUN_SPEED, VPetAnimations.run(stageTier), RUN_FPS);
    }

    private void moveTo(double targetX, double targetY, double speedPxPerSec, SpriteRole[] travelAnimation, double animationFps) {
        if (timer != null) timer.stop();

        // BUG bordes: se clampea SIEMPRE aquí, sin importar quién pidió el movimiento.
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double frameW = imageView.getFitWidth();
        double frameH = imageView.getFitHeight();
        double clampedX = Math.max(bounds.getMinX(), Math.min(bounds.getMaxX() - frameW, targetX));
        double clampedY = Math.max(bounds.getMinY(), Math.min(bounds.getMaxY() - frameH, targetY));

        // Se calcula TODO una sola vez aquí (no dentro del handle() de abajo),
        // para que no pueda oscilar recalculando el destino cada frame.
        double startX = stage.getX();
        double startY = stage.getY();
        double dx = clampedX - startX;
        double dy = clampedY - startY;
        double distance = Math.hypot(dx, dy);

        if (distance < 1) return;

        if (dx > 0) imageView.setScaleX(-1);
        else if (dx < 0) imageView.setScaleX(1);

        double durationSeconds = distance / speedPxPerSec;

        moving = true;
        animationPlayer.play(travelAnimation, animationFps);

        timer = new AnimationTimer() {
            private long startTime = -1;

            @Override
            public void handle(long now) {
                if (startTime < 0) startTime = now;
                double elapsed = (now - startTime) / 1_000_000_000.0;
                double t = Math.min(1.0, elapsed / durationSeconds);

                stage.setX(startX + dx * t);
                stage.setY(startY + dy * t);

                if (t >= 1.0) {
                    stop();
                    moving = false;
                    animationPlayer.play(defaultAnimation, 2.0);
                }
            }
        };
        timer.start();
    }

    public void stopMovement() {
        if (timer != null) timer.stop();
        moving = false;
        animationPlayer.play(defaultAnimation, 2.0);
    }
}