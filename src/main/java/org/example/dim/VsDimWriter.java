package org.example.dim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Escribe la VS DIM de vuelta al Vital Bracelet. Parte SIEMPRE de los bytes
 * de la VS DIM original (recién extraída) y cambia solo unas pocas palabras
 * del bloque de estado 0x40000 (u16 LE, con NOT como todo el archivo).
 *
 * Dos tipos de archivo, con dos checksums distintos en 0x120000 (ambos
 * guardados con NOT):
 *
 * 1. VS DIM "de transferencia" (la que sale del VB del dueño): checksum =
 *    suma de 16 bits de todos los u16 (ya sin el NOT) de [0x10000,
 *    0x120000). Confirmado en 5 archivos reales.
 *
 * 2. REPORTE DE BATALLA (lo que escribe el VB que pelea CONTRA la tarjeta;
 *    así recibe el dueño el resultado -- secc. 27 y "Global Connect" del mod
 *    Digimon Link). Descifrado con 3 reportes reales contra Hedorah:
 *      [0] = 2           hay reporte (0 = sin pelear)
 *      [4] = etapa del Digimon que peleó contra la tarjeta (SUPUESTO: con
 *            MagnaKidmon valía 5 = su etapa, pero también su
 *            secondPoolBattleChance; ver CLAUDE.md)
 *      [5] = 1 si ganó el Digimon de la tarjeta, 0 si perdió
 *      [6] = 0
 *    checksum = suma de 16 bits SOLO del bloque de estado [0x40000, 0x41000).
 *    Los Vital Values NO se tocan: el VB del dueño calcula él la recompensa o
 *    el castigo al leer el reporte.
 *
 * Escribir Vital Values nuevos en la tarjeta (writeWithVitalValues) produce
 * un archivo válido que el VB acepta pero IGNORA (probado 2026-09-28); queda
 * solo como herramienta de formato.
 */
public final class VsDimWriter {

    private static final int FILE_SIZE = 4 * 1024 * 1024;
    private static final int STATE_OFFSET = 0x40000;
    private static final int STATE_END = 0x41000;
    private static final int VITAL_VALUES_OFFSET = STATE_OFFSET + 1 * 2;
    private static final int REPORT_FLAG_OFFSET = STATE_OFFSET;              // [0]
    private static final int RIVAL_OFFSET = STATE_OFFSET + 4 * 2;            // [4]
    private static final int CARD_WON_OFFSET = STATE_OFFSET + 5 * 2;         // [5]
    private static final int POWER_OFFSET = STATE_OFFSET + 6 * 2;            // [6]
    private static final int REPORT_PRESENT = 2;
    private static final int CHECKSUM_FROM = 0x10000;
    private static final int CHECKSUM_OFFSET = 0x120000;

    private VsDimWriter() {}

    /**
     * Reporte de UNA batalla, como lo escribiría el VB rival: el Digimon de la
     * tarjeta peleó contra un Digimon de etapa {@code rivalStage} y ganó o
     * perdió. {@code source} debe ser una VS DIM de transferencia sin pelear.
     */
    public static void writeBattleReport(Path source, Path target, int rivalStage, boolean cardWon) throws IOException {
        if (rivalStage < 0 || rivalStage > 5) throw new IOException("Etapa del rival fuera de rango: " + rivalStage);
        byte[] raw = readTransferFile(source);
        putNot16(raw, REPORT_FLAG_OFFSET, REPORT_PRESENT);
        putNot16(raw, RIVAL_OFFSET, rivalStage);
        putNot16(raw, CARD_WON_OFFSET, cardWon ? 1 : 0);
        putNot16(raw, POWER_OFFSET, 0);
        putNot16(raw, CHECKSUM_OFFSET, stateChecksum(raw));
        Files.write(target, raw, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Copia exacta de la VS DIM original (para devolver al Digimon sin resultado). */
    public static void copyUnchanged(Path source, Path target) throws IOException {
        Files.write(target, readTransferFile(source), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Copia con los Vital Values cambiados. OJO: el VB ignora este valor al recibir la tarjeta. */
    public static void writeWithVitalValues(Path source, Path target, int vitalValues) throws IOException {
        if (vitalValues < 0 || vitalValues > 0xFFFF) throw new IOException("Vital Values fuera de rango: " + vitalValues);
        byte[] raw = readTransferFile(source);
        putNot16(raw, VITAL_VALUES_OFFSET, vitalValues);
        putNot16(raw, CHECKSUM_OFFSET, checksum(raw));
        Files.write(target, raw, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** true si el archivo ya trae un reporte de batalla ([0] ≠ 0): no es una VS DIM "sin pelear". */
    public static boolean hasBattleReport(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        return raw.length == FILE_SIZE && getNot16(raw, REPORT_FLAG_OFFSET) != 0;
    }

    /** Una VS DIM tal como sale del VB: 4 MB, checksum de transferencia válido y sin reporte de batalla. */
    private static byte[] readTransferFile(Path source) throws IOException {
        byte[] raw = Files.readAllBytes(source);
        if (raw.length != FILE_SIZE) throw new IOException(source.getFileName() + " no mide 4 MB.");
        if (getNot16(raw, REPORT_FLAG_OFFSET) != 0) {
            throw new IOException(source.getFileName() + " ya tiene un reporte de batalla: usa una VS DIM recién extraída del VB.");
        }
        if (!storedChecksumMatches(raw)) {
            // Un archivo que no salió tal cual del VB: mejor no fabricar uno "válido" a partir de él.
            throw new IOException(source.getFileName() + " tiene el checksum inválido (¿fue editado?). No se devolverá.");
        }
        return raw;
    }

    static boolean storedChecksumMatches(byte[] raw) {
        return getNot16(raw, CHECKSUM_OFFSET) == checksum(raw);
    }

    /** Checksum de una VS DIM de transferencia. */
    static int checksum(byte[] raw) {
        return sum(raw, CHECKSUM_FROM, CHECKSUM_OFFSET);
    }

    /** Checksum de un reporte de batalla: solo el bloque de estado. */
    static int stateChecksum(byte[] raw) {
        return sum(raw, STATE_OFFSET, STATE_END);
    }

    private static int sum(byte[] raw, int from, int to) {
        int sum = 0;
        for (int o = from; o < to; o += 2) sum += getNot16(raw, o);
        return sum & 0xFFFF;
    }

    private static int getNot16(byte[] raw, int offset) {
        return ~((raw[offset] & 0xFF) | ((raw[offset + 1] & 0xFF) << 8)) & 0xFFFF;
    }

    private static void putNot16(byte[] raw, int offset, int value) {
        int stored = ~value & 0xFFFF;
        raw[offset] = (byte) stored;
        raw[offset + 1] = (byte) (stored >>> 8);
    }
}
