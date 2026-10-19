package org.example.lab;

import org.example.chat.AppPaths;
import org.example.dim.VsDimData;
import org.example.dim.VsDimReader;
import org.example.dim.VsDimWriter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import org.example.sound.Sfx;
import org.example.sound.Sounds;

/**
 * Almacén del Laboratorio: "cápsulas" = COPIAS de VS DIM (el VB conserva a su
 * Digimon; confirmado por el usuario). Si una cápsula se devuelve al VB, el
 * VB recibe la versión de la cápsula, así que puede quedar desactualizada
 * respecto del VB (lo que pasó con la VS DIM vieja de MagnaKidmon).
 *
 * Cada cápsula = <id>.bin (la VS DIM tal cual) + <id>.properties (especie,
 * edad al guardar, fecha, notas, archivo de origen, récord pendiente, frases de
 * "primera vez" ya dichas) en
 * DigimonProjectData\laboratorio\capsulas. Eliminar la mueve a "papelera",
 * nunca la borra.
 */
public final class LabStorage {

    public static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Una cápsula ya leída. {@code data} = la VS DIM (sprites, stats, VV, trofeos). */
    public record Capsule(String id, Path file, String species, int ageDaysWhenSaved, LocalDateTime savedAt,
                          String notes, String origin, VsDimData data) {
        /** Edad hoy = la del VB al guardarla + los días que pasaron desde entonces. */
        public int ageDaysNow() {
            return ageDaysWhenSaved + (int) ChronoUnit.DAYS.between(savedAt.toLocalDate(), LocalDate.now());
        }
    }

    private LabStorage() {}

    public static Path folder() {
        return ensure(AppPaths.lab().resolve("capsulas"));
    }

    private static Path trash() {
        return ensure(AppPaths.lab().resolve("papelera"));
    }

    /** Todas las cápsulas, de la más nueva a la más vieja. Las que no se pueden leer se omiten con aviso. */
    public static List<Capsule> list() {
        List<Capsule> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder())) {
            for (Path meta : files.filter(p -> p.toString().endsWith(".properties")).toList()) {
                String id = meta.getFileName().toString().replace(".properties", "");
                try {
                    out.add(read(id));
                } catch (IOException e) {
                    System.out.println("[LAB] Cápsula " + id + " ilegible: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("[LAB] No se pudo leer el almacén: " + e.getMessage());
        }
        out.sort(Comparator.comparing(Capsule::savedAt).reversed());
        return out;
    }

    /**
     * Guarda una COPIA de la VS DIM como cápsula. Debe ser una VS DIM "sin
     * pelear" tal como sale del VB (no una devolución con reporte de batalla).
     */
    public static Capsule importVsDim(Path source, String species, int ageDays) throws IOException {
        VsDimReader.read(source); // valida que sea una VS DIM con Digimon
        if (VsDimWriter.hasBattleReport(source)) {
            throw new IOException(source.getFileName() + " ya trae un reporte de batalla (es una devolución, no un Digimon "
                    + "recién sacado del VB). Guarda la VS DIM tal como sale del VB.");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        Files.copy(source, folder().resolve(id + ".bin"), StandardCopyOption.COPY_ATTRIBUTES);
        Properties p = new Properties();
        p.setProperty("sha1", sha1(source));
        p.setProperty("especie", species == null || species.isBlank() ? "Digimon" : species.trim());
        p.setProperty("edadDias", String.valueOf(Math.max(0, ageDays)));
        p.setProperty("guardado", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        p.setProperty("notas", "");
        p.setProperty("origen", source.getFileName().toString());
        writeMeta(id, p);
        Sounds.play(Sfx.RESPALDO); // como al respaldar un Digimon en el Vital Bracelet
        return read(id);
    }

    /** La cápsula con exactamente esta misma VS DIM (mismo contenido), si ya está guardada. */
    public static java.util.Optional<Capsule> findSame(Path source) throws IOException {
        String sha = sha1(source);
        for (Capsule c : list()) {
            Properties p = readMeta(c.id());
            String stored = p.getProperty("sha1", "");
            if (stored.isEmpty()) { // cápsulas guardadas antes de existir el campo
                stored = sha1(c.file());
                p.setProperty("sha1", stored);
                writeMeta(c.id(), p);
            }
            if (stored.equals(sha)) return java.util.Optional.of(c);
        }
        return java.util.Optional.empty();
    }

    public static java.util.Optional<Capsule> find(String id) {
        try {
            return java.util.Optional.of(read(id));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /** Récord pendiente del Digimon (DigimonProgress.toProperties), guardado en su cápsula. */
    public static void saveProgress(String id, Properties progress) {
        try {
            Properties p = readMeta(id);
            p.putAll(progress);
            writeMeta(id, p);
        } catch (IOException e) {
            System.out.println("[LAB] No se pudo guardar el récord de " + id + ": " + e.getMessage());
        }
    }

    public static Properties loadProgress(String id) {
        try {
            return readMeta(id);
        } catch (IOException e) {
            return new Properties();
        }
    }

    /** Un dato suelto de la cápsula (p. ej. las frases de "primera vez" ya usadas). Vacío si no está. */
    public static String loadValue(String id, String key) {
        try {
            return readMeta(id).getProperty(key, "");
        } catch (IOException e) {
            return "";
        }
    }

    public static void saveValue(String id, String key, String value) {
        try {
            Properties p = readMeta(id);
            p.setProperty(key, value == null ? "" : value);
            writeMeta(id, p);
        } catch (IOException e) {
            System.out.println("[LAB] No se pudo guardar " + key + " de " + id + ": " + e.getMessage());
        }
    }

    /** Tras RETIRAR: el saldo ya viajó al VB en el reporte, se empieza de cero. */
    public static void resetProgress(String id) {
        try {
            Properties p = readMeta(id);
            p.stringPropertyNames().stream().filter(k -> k.startsWith("progreso.")).forEach(p::remove);
            writeMeta(id, p);
        } catch (IOException e) {
            System.out.println("[LAB] No se pudo vaciar el récord de " + id + ": " + e.getMessage());
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            return java.util.HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static void saveNotes(Capsule capsule, String notes) throws IOException {
        Properties p = readMeta(capsule.id());
        p.setProperty("notas", notes == null ? "" : notes);
        writeMeta(capsule.id(), p);
    }

    /** A la papelera del Laboratorio (se puede recuperar a mano). */
    public static void delete(Capsule capsule) throws IOException {
        for (String ext : new String[]{".bin", ".properties"}) {
            Path f = folder().resolve(capsule.id() + ext);
            if (Files.exists(f)) Files.move(f, trash().resolve(capsule.id() + ext), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Copia lista para pasar a la tarjeta, en la carpeta de devoluciones. */
    public static Path exportForVb(Capsule capsule) throws IOException {
        String species = capsule.species().replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (species.isEmpty()) species = "Digimon";
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
        Path target = AppPaths.returns().resolve("VS DIM " + species + " " + when + " (capsula).bin");
        Files.copy(capsule.file(), target);
        Sounds.play(Sfx.RESPALDO);
        return target;
    }

    private static Capsule read(String id) throws IOException {
        Properties p = readMeta(id);
        Path bin = folder().resolve(id + ".bin");
        LocalDateTime saved;
        try {
            saved = LocalDateTime.parse(p.getProperty("guardado", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            saved = LocalDateTime.now();
        }
        int age;
        try {
            age = Integer.parseInt(p.getProperty("edadDias", "0").trim());
        } catch (NumberFormatException e) {
            age = 0;
        }
        return new Capsule(id, bin, p.getProperty("especie", "Digimon"), age, saved,
                p.getProperty("notas", ""), p.getProperty("origen", ""), VsDimReader.read(bin));
    }

    private static Properties readMeta(String id) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(folder().resolve(id + ".properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static void writeMeta(String id, Properties p) throws IOException {
        try (Writer w = Files.newBufferedWriter(folder().resolve(id + ".properties"), StandardCharsets.UTF_8)) {
            p.store(w, "Cápsula del Laboratorio (copia de una VS DIM)");
        }
    }

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.out.println("[LAB] No se pudo crear " + dir + ": " + e.getMessage());
        }
        return dir;
    }
}
