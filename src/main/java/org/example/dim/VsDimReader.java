package org.example.dim;

import com.github.cfogrady.vb.dim.sprite.SpriteData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lector propio de archivos VS DIM (tarjeta con un Digimon ya criado,
 * extraído del Vital Bracelet). VB-DIM-Reader NO puede abrirlos: en una VS
 * DIM la zona de sprites no trae el paquete GP_SPIFI con su tabla de
 * punteros, y la librería truena en UnorderedSpriteReader.
 *
 * Formato confirmado comparando byte a byte "dim vs.bin" (vacía),
 * "VS DIM Dynasmon.bin" y "VS DIM MagnaKidmon.bin" (todo el archivo con NOT
 * de bits, u16 little endian, igual que una DIM normal):
 *
 *   0x10000  dword de flags VS: distinto de 0 = hay un Digimon guardado
 *            (documentado por GMMan en VB-DIM-Reader issue #1).
 *   0x30000  tabla de stats COMPLETA de la DIM de origen (17 x 12 u16, mismo
 *            formato que una DIM normal).
 *   0x40000  estado del Digimon transferido (la librería lo leería como una
 *            evolución falsa):
 *              [1] Vital Values          (confirmado: 4997 / 9011 en el VB)
 *              [2] 100 en los 3 archivos (sin confirmar -- ¿humor?)
 *              [3] slot del Digimon      (confirmado con sus sprites)
 *              [6] Power Trophies        (mod Digimon Link, confirmado: 35)
 *   0x60000  tabla de dimensiones de sprites COMPLETA de la DIM de origen.
 *   0x100000 SOLO los sprites del Digimon transferido, RGB565 crudo,
 *            consecutivos, sin índice. Sus tamaños salen de 0x60000.
 *
 * NO contiene: edad, ID de la DIM de origen, ni sprites de los demás slots.
 */
public final class VsDimReader {

    private static final int FILE_SIZE = 4 * 1024 * 1024;
    private static final int VS_FLAGS_OFFSET = 0x10000;
    private static final int STATS_OFFSET = 0x30000;
    private static final int STATE_OFFSET = 0x40000;
    private static final int DIMENSIONS_OFFSET = 0x60000;
    private static final int SPRITES_OFFSET = 0x100000;

    private static final int STATS_ENTRY_WORDS = 12;
    private static final int MAX_CHARACTERS = 23;
    /** Logo, fondo y 8 sprites de huevo, antes del primer personaje. */
    private static final int SPRITES_BEFORE_CHARACTERS = 10;
    private static final int NONE_VALUE = 0xFFFF;

    private VsDimReader() {}

    public static VsDimData read(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        if (raw.length != FILE_SIZE) {
            throw new IOException("Una VS DIM mide 4MB; " + path.getFileName() + " mide " + raw.length + " bytes.");
        }
        byte[] data = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) data[i] = (byte) ~raw[i];

        if (isNormalDimCard(data)) {
            throw new IOException(path.getFileName() + " es una DIM card normal, no una VS DIM.");
        }
        if (u16(data, VS_FLAGS_OFFSET) == 0 && u16(data, VS_FLAGS_OFFSET + 2) == 0) {
            throw new IOException(path.getFileName() + " es una VS DIM vacía (sin Digimon guardado).");
        }

        List<VsDimData.StatsRow> stats = readStats(data);
        int slot = u16(data, STATE_OFFSET + 3 * 2);
        if (slot >= stats.size()) {
            throw new IOException("Slot " + slot + " fuera de la tabla de stats (" + stats.size() + " entradas).");
        }

        int vitalValues = u16(data, STATE_OFFSET + 1 * 2);
        int unknownStateValue = u16(data, STATE_OFFSET + 2 * 2);
        int powerTrophies = u16(data, STATE_OFFSET + 6 * 2);

        List<SpriteData.Sprite> sprites = readSlotSprites(data, stats, slot);

        return new VsDimData(path, slot, stats, sprites, vitalValues, powerTrophies, unknownStateValue);
    }

    private static List<VsDimData.StatsRow> readStats(byte[] data) {
        List<VsDimData.StatsRow> rows = new ArrayList<>();
        for (int i = 0; i < MAX_CHARACTERS; i++) {
            int base = STATS_OFFSET + i * STATS_ENTRY_WORDS * 2;
            int[] w = new int[STATS_ENTRY_WORDS];
            boolean allZero = true;
            for (int k = 0; k < STATS_ENTRY_WORDS; k++) {
                w[k] = u16(data, base + k * 2);
                if (w[k] != 0) allZero = false;
            }
            if (allZero) break;
            // Orden de CharacterStatsEntry (DimStatsReader): stage, unlockRequired,
            // attribute, type, smallAttackId, bigAttackId, dpStars, dp, hp, ap,
            // firstPoolBattleChance, secondPoolBattleChance.
            rows.add(new VsDimData.StatsRow(w[0], w[1] != NONE_VALUE && w[1] != 0, w[2], w[3], w[4], w[5],
                    w[6], w[7], w[8], w[9], w[10], w[11]));
        }
        return rows;
    }

    /** Baby I = 6 sprites, Baby II = 7, Child+ = 14 (mismo conteo que DimCardDataReader). */
    private static int spriteCountForStage(int stage) {
        if (stage == 0) return 6;
        if (stage == 1) return 7;
        return 14;
    }

    private static List<SpriteData.Sprite> readSlotSprites(byte[] data, List<VsDimData.StatsRow> stats, int slot)
            throws IOException {
        int firstIndex = SPRITES_BEFORE_CHARACTERS;
        for (int i = 0; i < slot; i++) firstIndex += spriteCountForStage(stats.get(i).stage());
        int count = spriteCountForStage(stats.get(slot).stage());

        List<SpriteData.Sprite> sprites = new ArrayList<>(count);
        int offset = SPRITES_OFFSET;
        for (int i = 0; i < count; i++) {
            int dimBase = DIMENSIONS_OFFSET + (firstIndex + i) * 4;
            int width = u16(data, dimBase);
            int height = u16(data, dimBase + 2);
            if (width == 0 || height == 0 || width == NONE_VALUE || height == NONE_VALUE) {
                throw new IOException("Dimensiones inválidas para el sprite " + i + " del slot " + slot);
            }
            int bytes = width * height * 2;
            if (offset + bytes > data.length) {
                throw new IOException("Los sprites del slot " + slot + " exceden el tamaño del archivo.");
            }
            byte[] pixels = new byte[bytes];
            System.arraycopy(data, offset, pixels, 0, bytes);
            offset += bytes;
            sprites.add(SpriteData.Sprite.builder().width(width).height(height).pixelData(pixels).build());
        }
        return sprites;
    }

    /**
     * true si el archivo tiene formato VS DIM (con o sin Digimon adentro).
     * Solo lee los 8 bytes necesarios, no el archivo completo.
     */
    public static boolean isVsDimFile(Path path) {
        try (var channel = java.nio.channels.FileChannel.open(path)) {
            if (channel.size() != FILE_SIZE) return false;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(8);
            channel.read(buffer, SPRITES_OFFSET);
            byte[] notApplied = new byte[SPRITES_OFFSET + 8];
            byte[] head = buffer.array();
            for (int i = 0; i < 8; i++) notApplied[SPRITES_OFFSET + i] = (byte) ~head[i];
            return !isNormalDimCard(notApplied);
        } catch (IOException e) {
            return false;
        }
    }

    /** Una DIM normal empieza su zona de sprites con el paquete "GP_SPIFI"; una VS DIM, con píxeles crudos. */
    public static boolean isNormalDimCard(byte[] notApplied) {
        byte[] magic = "GP_SPIFI".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < magic.length; i++) {
            if (notApplied[SPRITES_OFFSET + i] != magic[i]) return false;
        }
        return true;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
