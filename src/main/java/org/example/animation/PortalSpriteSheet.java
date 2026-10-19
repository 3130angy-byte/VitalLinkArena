package org.example.animation;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

public class PortalSpriteSheet {
    public static final int FRAME_WIDTH = 104;  // antes 90
    public static final int FRAME_HEIGHT = 110;
    public static final int MATERIALIZE_FRAMES = 6;
    public static final int LOOP_FRAMES = 8;
    public static final int TOTAL_FRAMES = MATERIALIZE_FRAMES + LOOP_FRAMES;

    private final Image[] frames = new Image[TOTAL_FRAMES];

    public PortalSpriteSheet(Image sheet) {
        PixelReader reader = sheet.getPixelReader();
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            frames[i] = new WritableImage(reader, i * FRAME_WIDTH, 0, FRAME_WIDTH, FRAME_HEIGHT);
        }
    }

    public Image getMaterializeFrame(int i) { return frames[i]; }
    public Image getLoopFrame(int i) { return frames[MATERIALIZE_FRAMES + i]; }
}