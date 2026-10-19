package org.example.animation;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

public class DimAnimationPlayer {
    private final ImageView view;
    private DimSpriteSet spriteSet;
    private Timeline timeline;

    public DimAnimationPlayer(ImageView view, DimSpriteSet spriteSet) {
        this.view = view;
        this.spriteSet = spriteSet;
    }

    public void setSpriteSet(DimSpriteSet spriteSet) {
        this.spriteSet = spriteSet;
    }

    public DimSpriteSet getSpriteSet() {
        return spriteSet;
    }

    public void play(SpriteRole[] roles, double fps) {
        stop();
        if (roles.length == 1) {
            view.setImage(spriteSet.get(roles[0]));
            return;
        }

        Duration frameDuration = Duration.seconds(1.0 / fps);
        timeline = new Timeline();
        timeline.setCycleCount(Animation.INDEFINITE);
        for (int i = 0; i < roles.length; i++) {
            SpriteRole role = roles[i];
            timeline.getKeyFrames().add(
                    new KeyFrame(frameDuration.multiply(i), e -> view.setImage(spriteSet.get(role)))
            );
        }
        timeline.getKeyFrames().add(new KeyFrame(frameDuration.multiply(roles.length)));
        timeline.play();
    }

    public void playOnce(SpriteRole[] roles, double fps, Runnable onFinished) {
        stop();
        if (roles.length == 1) {
            view.setImage(spriteSet.get(roles[0]));
            if (onFinished != null) onFinished.run();
            return;
        }

        Duration frameDuration = Duration.seconds(1.0 / fps);
        timeline = new Timeline();
        timeline.setCycleCount(1);
        for (int i = 0; i < roles.length; i++) {
            SpriteRole role = roles[i];
            timeline.getKeyFrames().add(
                    new KeyFrame(frameDuration.multiply(i), e -> view.setImage(spriteSet.get(role)))
            );
        }
        timeline.getKeyFrames().add(new KeyFrame(frameDuration.multiply(roles.length), e -> {
            if (onFinished != null) onFinished.run();
        }));
        timeline.play();
    }

    public void stop() {
        if (timeline != null) timeline.stop();
    }
}