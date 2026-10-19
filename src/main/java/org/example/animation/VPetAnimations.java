package org.example.animation;

public final class VPetAnimations {

    private VPetAnimations() {}

    public static SpriteRole[] idle(VPetStageTier stage) {
        return new SpriteRole[]{SpriteRole.IDLE_1, SpriteRole.IDLE_2};
    }

    public static SpriteRole[] walk(VPetStageTier stage) {
        if (stage == VPetStageTier.CHILD_PLUS) {
            return new SpriteRole[]{SpriteRole.WALK_1, SpriteRole.WALK_2};
        }
        return new SpriteRole[]{SpriteRole.IDLE_1, SpriteRole.IDLE_2};
    }

    public static SpriteRole[] run(VPetStageTier stage) {
        if (stage == VPetStageTier.CHILD_PLUS) {
            return new SpriteRole[]{SpriteRole.RUN_1, SpriteRole.RUN_2};
        }
        return new SpriteRole[]{SpriteRole.IDLE_1, SpriteRole.WALK_1};
    }

    public static SpriteRole[] celebrate(VPetStageTier stage) {
        return new SpriteRole[]{SpriteRole.IDLE_1, SpriteRole.VICTORY, SpriteRole.IDLE_1, SpriteRole.VICTORY};
    }

    public static SpriteRole[] lose(VPetStageTier stage) {
        return new SpriteRole[]{SpriteRole.IDLE_1, SpriteRole.SLEEP, SpriteRole.IDLE_1, SpriteRole.SLEEP};
    }

    /**
     * Regla única para toda la app: Child en adelante usa Attack real;
     * Baby I/Baby II usan Happy (=Victory) como sustituto, porque no tienen
     * sprite de Attack.
     */
    public static SpriteRole attackOrHappy(VPetStageTier stage) {
        return stage == VPetStageTier.CHILD_PLUS ? SpriteRole.ATTACK : SpriteRole.VICTORY;
    }
}