package org.example.online.client;

import com.github.cfogrady.vb.dim.sprite.SpriteData;
import javafx.scene.image.Image;

import org.example.dim.DimSpriteImageFactory;
import org.example.dim.DimVPetData;
import org.example.dim.VsDimData;
import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Base64;
import java.util.List;

/**
 * El Digimon de un jugador dentro de la sala: sus cuadros (Protocol.FRAME_*),
 * su rango (calculado por el servidor) y cómo camina. Desde el 2026-10-18
 * (pedido del usuario) NO hay avatar humano: el Digimon ES el jugador (lo
 * mueves con clic, va en la posición que manda el servidor; ver ride).
 *
 * Los cuadros viajan como píxeles RGB565 CRUDOS, los mismos bytes de la VS
 * DIM, y se decodifican con DimSpriteImageFactory (código propio). Nunca se
 * reciben archivos de imagen: así ningún jugador puede mandar un PNG/JPG
 * malicioso al decodificador de imágenes de los demás.
 *
 * Tamaño: los sprites DIM (hasta 64x56) son enormes frente a las casillas
 * de 16: en el MUNDO miden la mitad, pero la cámara de la sala acerca x2,
 * así que en pantalla se ven con sus píxeles originales 1:1.
 */
public final class LobbyDigimon {

    private static final long WALK_STEP_NANOS = 220_000_000L;
    private static final long IDLE_STEP_NANOS = 600_000_000L;
    /** Pose al volver de una pelea: IDLE_1 / VICTORY (o SLEEP) x2 a 3 por segundo, como en el escritorio. */
    private static final long REACTION_STEP_NANOS = 333_333_333L;
    private static final int REACTION_STEPS = 4;

    final String species;
    final String rank;
    final int attribute;
    /** Etapa cruda de la DIM (2 Child .. 5 Ultimate): fija los Vital Values que da o quita una batalla. */
    final int stage;
    /** Protocol.FRAME_* a tamaño COMPLETO (para el coliseo de las Batallas Oficiales). */
    final Image[] fullFrames;
    /** Sprite NAME en su tamaño nativo (pantallas del coliseo). */
    final Image nameImage;
    /** Huella de su aspecto (especie + cuadros): si cambia, el jugador cambió de Digimon y la sala lo anima con un portal. */
    final int look;
    /** Compañero del equipo (puesto 2), solo para el 2 vs 2; null si no trajo. No camina en la sala. */
    LobbyDigimon partner;

    double x = Double.NaN, y = Double.NaN;
    /** Los sprites DIM miran a la izquierda; a la derecha se espejan (convención del proyecto). */
    boolean facingRight = false;
    int frame = 0;
    /** Cuándo empezó su pose de victoria o derrota (-1 = no la está haciendo) y qué cuadro alterna con IDLE_1. */
    private long reactionStart = -1;
    private int reactionFrame = Protocol.FRAME_VICTORY;

    /** Es una COPIA del LAB (protocolo v11): se dibuja celeste y con píxeles celestes. */
    final boolean copy;
    /** Premio del campeón de un torneo encendido (protocolo v12): se ve al DOBLE, en pixel art. */
    final boolean big;

    private LobbyDigimon(String species, String rank, int attribute, int stage, Image[] fullFrames, Image nameImage, int look,
                         boolean copy, boolean big) {
        this.copy = copy;
        this.big = big;
        this.look = look;
        this.species = species;
        this.rank = rank;
        this.attribute = attribute;
        this.stage = stage;
        this.fullFrames = fullFrames;
        this.nameImage = nameImage;
    }

    // ---------------------------------------------------------------- enviar

    /**
     * Mensaje "digimon" a partir de la VS DIM. La especie la pone quien llama
     * (nunca el nombre del archivo; ver regla en CLAUDE.md). Stats NO se
     * envían: el rango lo calcula el servidor con los Power Trophies.
     */
    public static JSONObject payloadFrom(VsDimData vs, String species) {
        VsDimData.StatsRow c = vs.character();
        return payload(vs.sprites(), c.stage(), c.attribute(), c.dp(), c.hp(), c.ap(), c.smallAttackId(),
                c.bigAttackId(), c.activityType(), vs.powerTrophies(), species);
    }

    /**
     * 0.0.4 (crianza): el Digimon criado, con los sprites y stats de SU DIM card
     * en el slot actual. Los "trofeos" son sus PP de entrenamiento (lo que la
     * DIM pide como trofeos para evolucionar): dan el rango y el bono de la
     * Batalla Libre igual que los Power Trophies.
     */
    public static JSONObject payloadFrom(DimVPetData card, int slot, int trophies, String species) {
        var e = card.getCard().getCharacterStats().getCharacterEntries().get(slot);
        return payload(card.getSpritesForSlot(slot), card.getStageForSlot(slot), e.getAttribute(), e.getDp(),
                e.getHp(), e.getAp(), e.getSmallAttackId(), e.getBigAttackId(), e.getType(), trophies, species);
    }

    /** Igual, diciendo si es una COPIA del LAB (protocolo v11: los demás la ven celeste). */
    public static JSONObject payloadFrom(DimVPetData card, int slot, int trophies, String species, boolean copy) {
        return payloadFrom(card, slot, trophies, species).put("copy", copy);
    }

    /** Un Baby no tiene DP/HP/AP en la DIM (65535 = "no aplica"): se mandan valores válidos (no pelea). */
    private static int stat(int value, int min) {
        return value == NONE_VALUE || value < min ? min : Math.min(value, 9999);
    }

    private static final int NONE_VALUE = 0xFFFF;

    private static JSONObject payload(List<SpriteData.Sprite> sprites, int stage, int attribute, int dp, int hp, int ap,
                                      int small, int big, int activity, int trophies, String species) {
        // Orden: IDLE_1, IDLE_2, WALK_1, WALK_2, ATTACK, DODGE, VICTORY, SLEEP (Protocol.FRAME_*).
        // Child+: NAME 0, IDLE_1 1, IDLE_2 2, WALK_1 3, WALK_2 4 ... VICTORY 9, SLEEP 10, ATTACK 11, DODGE 12.
        // Baby: NAME, IDLE_1, IDLE_2, WALK_1, VICTORY 4, SLEEP 5 (sin WALK_2 ni ataque: no pelean, se repiten cuadros).
        int[] indices = stage <= 1 ? new int[]{1, 2, 3, 1, 1, 1, 4, 5} : new int[]{1, 2, 3, 4, 11, 12, 9, 10};
        JSONArray frames = new JSONArray();
        for (int i : indices) frames.put(pixels(sprites.get(i)));
        return Protocol.msg("digimon")
                .put("species", species)
                .put("attribute", attribute)
                .put("stage", stage)
                .put("powerTrophies", Math.max(0, Math.min(trophies, Protocol.MAX_POWER_TROPHIES)))
                .put("frames", frames)
                .put("nameSprite", pixels(sprites.get(0)))
                // Stats: SOLO los usa el servidor para calcular Batallas Oficiales; nunca se reparten.
                .put("stats", new JSONObject().put("dp", stat(dp, 0)).put("hp", stat(hp, 1)).put("ap", stat(ap, 0))
                        .put("small", small).put("big", big).put("activity", activity >= 0 && activity <= 4 ? activity : 2));
    }

    /** Mensaje "digimon" con TU EQUIPO a partir de dos mensajes ya armados (el segundo puede ser null). */
    public static JSONObject team(JSONObject primary, JSONObject secondary) {
        if (secondary != null) {
            JSONObject partner = new JSONObject(secondary.toString());
            partner.remove("t");
            primary.put("partner", partner);
        }
        return primary;
    }

    /**
     * Mensaje "digimon" con TU EQUIPO: el puesto 1 (el que te sigue y pelea
     * las batallas tipo VB) y, si hay, el puesto 2 como "partner" (solo para
     * el 2 vs 2). Se reenvía cuando cambias el equipo en la PC.
     */
    public static JSONObject teamPayload(VsDimData primary, String primarySpecies,
                                         VsDimData secondary, String secondarySpecies) {
        JSONObject message = payloadFrom(primary, primarySpecies);
        if (secondary != null) {
            JSONObject partner = payloadFrom(secondary, secondarySpecies);
            partner.remove("t");
            message.put("partner", partner);
        }
        return message;
    }

    private static JSONObject pixels(SpriteData.Sprite s) {
        return new JSONObject().put("w", s.getWidth()).put("h", s.getHeight())
                .put("px", Base64.getEncoder().encodeToString(s.getPixelData()));
    }

    // ---------------------------------------------------------------- recibir

    /** Desde el mensaje que reparte el servidor (ya validado allá; aquí se revisa de nuevo lo mínimo). */
    static LobbyDigimon fromMessage(JSONObject m) {
        JSONArray arr = m.getJSONArray("frames");
        if (arr.length() != Protocol.DIGIMON_FRAMES) throw new IllegalArgumentException("cuadros incorrectos");
        // Protocolo v11: una COPIA del LAB se ve celeste para todos (sala, coliseo y ARENA usan estos cuadros).
        boolean copy = m.opt("copy") instanceof Boolean b && b;
        Image[] full = new Image[Protocol.DIGIMON_FRAMES];
        for (int i = 0; i < full.length; i++) {
            SpriteData.Sprite sprite = decode(arr.getJSONObject(i), Protocol.MAX_FRAME_WIDTH, Protocol.MAX_FRAME_HEIGHT);
            full[i] = DimSpriteImageFactory.toImage(sprite, sprite.getWidth(), sprite.getHeight());
            if (copy) full[i] = org.example.animation.GhostLook.tint(full[i]);
        }
        SpriteData.Sprite name = decode(m.getJSONObject("nameSprite"),
                Protocol.MAX_NAME_SPRITE_WIDTH, Protocol.MAX_NAME_SPRITE_HEIGHT);
        LobbyDigimon d = new LobbyDigimon(m.optString("species", "Digimon"), m.optString("rank", "-"),
                m.optInt("attribute", 0), m.optInt("stage", 2), full, DimSpriteImageFactory.toNativeImage(name),
                (m.optString("species", "") + copy + arr).hashCode(), copy, m.opt("big") instanceof Boolean big && big);
        JSONObject p = m.optJSONObject("partner");
        if (p != null) d.partner = fromMessage(p);
        return d;
    }

    /** Píxeles RGB565 crudos -> sprite, revisando tamaño y largo (el servidor ya lo validó; aquí, otra vez). */
    private static SpriteData.Sprite decode(JSONObject f, int maxW, int maxH) {
        int w = f.getInt("w"), h = f.getInt("h");
        if (w < 1 || h < 1 || w > maxW || h > maxH) throw new IllegalArgumentException("sprite fuera de tamaño");
        byte[] px = Base64.getDecoder().decode(f.getString("px"));
        if (px.length != w * h * 2) throw new IllegalArgumentException("sprite incompleto");
        return SpriteData.Sprite.builder().width(w).height(h).pixelData(px).build();
    }

    /** El servidor manda "-" cuando no llega al rango C (menos de 10 puntos). */
    static boolean hasRank(String rank) {
        return rank != null && rank.length() == 1 && "CBAS".contains(rank);
    }

    /** "rango B" o "sin rango", para los textos. */
    static String rankText(String rank) {
        return hasRank(rank) ? "rango " + rank : "sin rango";
    }

    // ---------------------------------------------------------------- caminar y animar

    /**
     * Sin avatar (pedido del usuario, 2026-10-18): el Digimon ES el jugador. Va en la posición que
     * dibuja la sala (la del servidor, interpolada), mira hacia donde camina, alterna WALK_1/WALK_2 al
     * andar e IDLE_1/IDLE_2 quieto; la pose de victoria o derrota al volver de una pelea tiene prioridad.
     */
    void ride(double x, double y, boolean facingRight, boolean walking, long now) {
        this.x = x;
        this.y = y;
        this.facingRight = facingRight;
        if (reactionStart >= 0 && now - reactionStart < REACTION_STEPS * REACTION_STEP_NANOS) {
            frame = (now - reactionStart) / REACTION_STEP_NANOS % 2 == 0 ? Protocol.FRAME_IDLE_1 : reactionFrame;
            return;
        }
        frame = walking
                ? 2 + (int) ((now / WALK_STEP_NANOS) % 2)
                : (int) ((now / IDLE_STEP_NANOS) % 2);
    }

    /** Mismo Digimon con datos nuevos (p. ej. solo cambió el compañero): sigue donde estaba, sin saltar. */
    void takePlaceOf(LobbyDigimon old) {
        x = old.x;
        y = old.y;
        facingRight = old.facingRight;
        frame = old.frame;
        reactionStart = old.reactionStart;
        reactionFrame = old.reactionFrame;
    }

    /**
     * Al volver a la sala de una pelea (pedido del usuario, 2026-10-05): pose de
     * victoria (IDLE_1 / VICTORY) o de derrota (IDLE_1 / SLEEP), igual que en el escritorio.
     */
    void react(boolean won) {
        reactionStart = System.nanoTime();
        reactionFrame = won ? Protocol.FRAME_VICTORY : Protocol.FRAME_SLEEP;
    }

    /** Lo deja quieto en un punto (al salir del portal); desde ahí sigue caminando como el jugador. */
    void placeAt(double x, double y, boolean facingRight) {
        this.x = x;
        this.y = y;
        this.facingRight = facingRight;
    }

    /**
     * Cuánto mide en el MUNDO cada píxel de su sprite: la mitad (la cámara x2 lo deja 1:1 en pantalla) o, con el
     * premio encendido, uno entero (2x2 en pantalla). Siempre un múltiplo entero en pantalla: sigue siendo pixel art.
     */
    double worldScale() {
        return big ? 1.0 : 0.5;
    }

    /** Cuadro actual a tamaño COMPLETO (la sala lo dibuja a la mitad del mundo = píxeles 1:1 en pantalla). */
    Image currentFrame() {
        return fullFrames[frame];
    }
}
