package org.example.lab;

import com.github.cfogrady.vb.dim.sprite.SpriteData;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import org.example.chat.AppPaths;
import org.example.dim.DimSpriteImageFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * Digidex del Laboratorio: cada especie VISTA (tus Digimon, tus cápsulas y
 * los rivales de Batalla aleatoria y ARENA). Se identifica por los píxeles
 * de su sprite NAME (el mismo criterio que la lectura de especie: nunca el
 * nombre del archivo), que se guarda como PNG para mostrarlo. La especie en
 * texto solo se conoce si alguien la escribió o la leyó; si no, se muestra
 * el sprite.
 *
 * DigimonProjectData\laboratorio\digidex\<hash>.png + digidex.properties
 * (hash = veces|especie|primera vez).
 */
public final class Digidex {

    public record Entry(String hash, int timesSeen, String species, String firstSeen, Path image) {}

    private Digidex() {}

    private static Path folder() {
        Path dir = AppPaths.lab().resolve("digidex");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // se avisa al escribir
        }
        return dir;
    }

    private static Path index() {
        return folder().resolve("digidex.properties");
    }

    /** Registra un avistamiento. {@code species} puede ser null (rival sin nombre en texto). */
    public static synchronized void seen(SpriteData.Sprite nameSprite, String species) {
        try {
            String hash = hash(nameSprite);
            Properties p = load();
            String[] parts = p.getProperty(hash, "0||" + LocalDate.now()).split("\\|", -1);
            int times = parse(parts[0]) + 1;
            String known = parts.length > 1 ? parts[1] : "";
            if ((known.isEmpty() || known.equals("Digimon")) && species != null && !species.isBlank()
                    && !species.equals("Digimon")) {
                known = species.trim().replace("|", "");
            }
            String first = parts.length > 2 ? parts[2] : LocalDate.now().toString();
            p.setProperty(hash, times + "|" + known + "|" + first);
            save(p);
            Path png = folder().resolve(hash + ".png");
            if (!Files.exists(png)) writePng(DimSpriteImageFactory.toNativeImage(nameSprite), png);
        } catch (Exception e) {
            System.out.println("[DIGIDEX] No se pudo registrar: " + e.getMessage());
        }
    }

    public static synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        Properties p = load();
        for (String hash : p.stringPropertyNames()) {
            String[] parts = p.getProperty(hash).split("\\|", -1);
            out.add(new Entry(hash, parse(parts[0]), parts.length > 1 ? parts[1] : "",
                    parts.length > 2 ? parts[2] : "", folder().resolve(hash + ".png")));
        }
        out.sort((a, b) -> Integer.compare(b.timesSeen(), a.timesSeen()));
        return out;
    }

    private static String hash(SpriteData.Sprite s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update((s.getWidth() + "x" + s.getHeight()).getBytes(StandardCharsets.US_ASCII));
        md.update(s.getPixelData());
        return HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }

    private static void writePng(Image image, Path target) throws IOException {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = image.getPixelReader();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out.setRGB(x, y, r.getArgb(x, y));
        javax.imageio.ImageIO.write(out, "png", target.toFile());
    }

    private static Properties load() {
        Properties p = new Properties();
        if (!Files.exists(index())) return p;
        try (Reader r = Files.newBufferedReader(index(), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            System.out.println("[DIGIDEX] No se pudo leer: " + e.getMessage());
        }
        return p;
    }

    private static void save(Properties p) throws IOException {
        try (Writer w = Files.newBufferedWriter(index(), StandardCharsets.UTF_8)) {
            p.store(w, "Digidex: hash del sprite NAME = veces|especie|primera vez");
        }
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
