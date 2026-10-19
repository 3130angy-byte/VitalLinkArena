package org.example.online.client;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

import org.example.animation.PortalSpriteSheet;

/**
 * Cambio del Digimon de un jugador POR UN PORTAL dentro de la sala (pedido
 * del usuario): se abre un portal junto a su Digimon, este camina hacia él y
 * se desvanece, el portal QUEDA ABIERTO y por él sale el nuevo; después se
 * cierra. Misma coreografía que TeleportAnimator en el escritorio (8 pasos de
 * opacidad, deja de alternar WALK al 30 %, portal espejado según hacia dónde
 * se camina), en unidades del mundo de la sala.
 *
 * Solo visual: cada cliente lo dibuja con los mensajes "digimon" que ya
 * reparte el servidor. Para TU equipo la salida empieza al instante (aún no
 * se sabe quién llega: arriving = null, el portal espera abierto); para los
 * demás jugadores se ve completo cuando llega su Digimon nuevo.
 */
final class LobbyPortalSwap {

    /** El portal mide la mitad en el mundo: con la cámara x2 se ve con sus píxeles 1:1, como el Digimon. */
    private static final double PORTAL_W = PortalSpriteSheet.FRAME_WIDTH / 2.0;
    private static final double PORTAL_H = PortalSpriteSheet.FRAME_HEIGHT / 2.0;
    private static final double MATERIALIZE_STEP = 0.09;
    private static final double OPEN_SECONDS = PortalSpriteSheet.MATERIALIZE_FRAMES * MATERIALIZE_STEP;
    private static final double LOOP_STEP = 0.12;
    private static final double IDLE_STEP = 0.45;
    private static final double WALK_STEP = 0.16;
    /** 90 px/s del escritorio a la escala del mundo de la sala (los sprites miden la mitad). */
    private static final double WALK_SPEED = 45;
    private static final double CLEARANCE = 8;
    private static final double[] OPACITY_STEPS = {1.0, 0.9, 0.8, 0.7, 0.5, 0.3, 0.1, 0.0};
    private static final int FREEZE_STEP = 5; // paso del 30 %: deja de alternar WALK
    private static final double FADE_STEP = 0.13;
    private static final double FADE_SECONDS = OPACITY_STEPS.length * FADE_STEP;
    /** Durante el desvanecimiento avanza media anchura del portal (hacia su interior). */
    private static final double FADE_ADVANCE = PORTAL_W / 2;
    /** Pausa con el portal abierto y vacío entre el que se va y el que llega. */
    private static final double ARRIVAL_PAUSE = 0.3;
    /** Si el Digimon nuevo nunca llega (el servidor rechazó el cambio), vuelve a salir el que ya estaba. */
    private static final double WAIT_TIMEOUT = 15;

    private final LobbyDigimon leaving;
    private LobbyDigimon arriving;
    private final long startNanos;
    /** Hacia dónde camina el que se va: +1 derecha, -1 izquierda (hacia donde miraba: el portal se abre delante). */
    private final int dir;
    private final double startX, feetY, portalX, entryX, walkSeconds;
    /**
     * El que llega sale del portal y camina HASTA LA POSICIÓN DEL JUGADOR (donde estaba el que se fue): sin
     * avatar (2026-10-18), el Digimon es el jugador y no puede quedar corrido de su lugar.
     */
    private final double outWalkDistance, outWalkSeconds;
    private double arriveAt = -1, closeAt = -1;

    LobbyPortalSwap(LobbyDigimon leaving, boolean facingRight, LobbyDigimon arriving, long now) {
        this.leaving = leaving;
        this.arriving = arriving;
        this.startNanos = now;
        this.dir = facingRight ? 1 : -1;
        this.startX = leaving.x;
        this.feetY = leaving.y;
        double halfWidth = leaving.fullFrames[0].getWidth() / 4; // mitad del sprite, que en el mundo mide la mitad
        this.portalX = startX + dir * (halfWidth + CLEARANCE + PORTAL_W / 2);
        this.entryX = portalX - dir * PORTAL_W / 2; // borde del portal
        this.walkSeconds = Math.max(0.3, Math.abs(entryX - startX) / WALK_SPEED);
        this.outWalkDistance = Math.max(0, Math.abs(portalX - startX) - FADE_ADVANCE);
        this.outWalkSeconds = Math.max(0.3, outWalkDistance / WALK_SPEED);
    }

    /** Llegó el Digimon nuevo (tu equipo, desde el servidor): sale por el portal que quedó abierto. */
    void arrive(LobbyDigimon d) {
        if (arriving == null) arriving = d;
    }

    boolean waiting() {
        return arriving == null;
    }

    double sortY() {
        return feetY;
    }

    /** Alto en el mundo del que se va (para poner el nombre del jugador encima durante el cambio). */
    double height() {
        return leaving.fullFrames[0].getHeight() * leaving.worldScale();
    }

    private double seconds(long now) {
        return (now - startNanos) / 1e9;
    }

    private double leaveEnd() {
        return OPEN_SECONDS + walkSeconds + FADE_SECONDS;
    }

    private double endX() {
        return portalX - dir * (FADE_ADVANCE + outWalkDistance);
    }

    /**
     * Avanza los tiempos. Devuelve true al terminar: el nuevo quedó en la posición
     * del jugador, con el portal cerrado, y desde ahí camina como él.
     */
    boolean update(long now, LobbyDigimon current) {
        double t = seconds(now);
        if (arriving == null && t > leaveEnd() + WAIT_TIMEOUT) arriving = current != null ? current : leaving;
        if (arriving != null && arriveAt < 0 && t >= leaveEnd()) {
            arriveAt = Math.max(t, leaveEnd() + ARRIVAL_PAUSE);
            closeAt = arriveAt + FADE_SECONDS + outWalkSeconds;
        }
        if (closeAt >= 0 && t >= closeAt + OPEN_SECONDS) {
            arriving.placeAt(endX(), feetY, dir < 0);
            return true;
        }
        return false;
    }

    /** Portal detrás, y delante el que se va o el que llega (coordenadas del mundo, cámara ya aplicada). */
    void draw(GraphicsContext g, long now, double feetOffset) {
        double t = seconds(now);
        double feet = feetY + feetOffset;
        drawPortal(g, t, feet - height() / 2);

        if (t < leaveEnd()) {
            double x;
            int frame;
            double alpha = 1;
            if (t < OPEN_SECONDS) { // quieto mientras se abre el portal
                x = startX;
                frame = (int) (t / IDLE_STEP) % 2;
            } else if (t < OPEN_SECONDS + walkSeconds) { // camina hasta el borde del portal
                x = startX + (entryX - startX) * (t - OPEN_SECONDS) / walkSeconds;
                frame = walkFrame(t);
            } else { // entra desvaneciéndose
                int i = Math.min(OPACITY_STEPS.length - 1, (int) ((t - OPEN_SECONDS - walkSeconds) / FADE_STEP));
                alpha = OPACITY_STEPS[i];
                x = entryX + dir * FADE_ADVANCE * i / (OPACITY_STEPS.length - 1);
                frame = 2 + Math.min(i, FREEZE_STEP - 1) % 2;
            }
            sprite(g, leaving.fullFrames[frame], leaving.worldScale(), x, feet, dir > 0, alpha);
        }

        if (arriveAt >= 0 && t >= arriveAt) {
            double ta = t - arriveAt;
            double x;
            int frame;
            double alpha = 1;
            if (ta < FADE_SECONDS) { // aparece desde el centro del portal
                int i = Math.min(OPACITY_STEPS.length - 1, (int) (ta / FADE_STEP));
                alpha = OPACITY_STEPS[OPACITY_STEPS.length - 1 - i];
                x = portalX - dir * FADE_ADVANCE * i / (OPACITY_STEPS.length - 1);
                frame = 2 + i % 2;
            } else if (ta < FADE_SECONDS + outWalkSeconds) { // camina de vuelta al lugar del jugador
                double f = (ta - FADE_SECONDS) / outWalkSeconds;
                x = portalX - dir * (FADE_ADVANCE + outWalkDistance * f);
                frame = walkFrame(t);
            } else { // quieto mientras se cierra el portal
                x = endX();
                frame = (int) (t / IDLE_STEP) % 2;
            }
            sprite(g, arriving.fullFrames[frame], arriving.worldScale(), x, feet, dir < 0, alpha);
        }
    }

    private void drawPortal(GraphicsContext g, double t, double centerY) {
        PortalSpriteSheet sheet = LobbyMapRenderer.portalSheet();
        Image frame;
        if (t < OPEN_SECONDS) {
            frame = sheet.getMaterializeFrame(Math.min(PortalSpriteSheet.MATERIALIZE_FRAMES - 1, (int) (t / MATERIALIZE_STEP)));
        } else if (closeAt >= 0 && t >= closeAt) {
            int k = (int) ((t - closeAt) / MATERIALIZE_STEP);
            if (k >= PortalSpriteSheet.MATERIALIZE_FRAMES) return;
            frame = sheet.getMaterializeFrame(PortalSpriteSheet.MATERIALIZE_FRAMES - 1 - k);
        } else {
            frame = sheet.getLoopFrame((int) ((t - OPEN_SECONDS) / LOOP_STEP) % PortalSpriteSheet.LOOP_FRAMES);
        }
        // Misma regla que TeleportAnimator: al entrar caminando a la derecha, el portal se espeja.
        g.save();
        g.translate(portalX, 0);
        if (dir > 0) g.scale(-1, 1);
        double k = leaving.big ? 2 : 1; // con el premio, el portal también al doble
        g.drawImage(frame, -PORTAL_W * k / 2, centerY - PORTAL_H * k / 2, PORTAL_W * k, PORTAL_H * k);
        g.restore();
    }

    private static int walkFrame(double t) {
        return 2 + (int) (t / WALK_STEP) % 2; // WALK_1 / WALK_2 (Protocol.FRAME_*)
    }

    /** Pies en (x, feet), a su tamaño en el mundo (la mitad, o entero con el premio); a la derecha se espeja. */
    private static void sprite(GraphicsContext g, Image frame, double worldScale, double x, double feet, boolean facingRight,
                               double alpha) {
        if (alpha <= 0) return;
        double w = frame.getWidth() * worldScale, h = frame.getHeight() * worldScale;
        g.save();
        g.setGlobalAlpha(alpha);
        g.translate(LobbyWindow.snap(x), 0); // en un píxel de pantalla exacto (pixel art parejo)
        if (facingRight) g.scale(-1, 1);
        g.drawImage(frame, -w / 2, LobbyWindow.snap(feet) - h, w, h);
        g.restore();
    }
}
