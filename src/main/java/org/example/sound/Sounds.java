package org.example.sound;

import javafx.application.Platform;
import javafx.scene.media.AudioClip;
import org.example.chat.AppPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reproduce los efectos de sonido (pedido del usuario, 2026-10-04).
 *
 * Los archivos NO vienen con el programa: se leen de la carpeta
 * DigimonProjectData\sounds (1.mp3 ... 27.mp3, o .wav). Son sonidos del Vital
 * Bracelet (de Bandai), así que no se publican ni se empaquetan, igual que
 * las DIM cards. Sin la carpeta, o si un archivo falta, simplemente no suena.
 *
 * config\sonidos.properties: activado (también desde el menú), volumen (0-1)
 * y qué archivo suena en cada momento (ver {@link Sfx}).
 */
public final class Sounds {

    private static final Path CONFIG = AppPaths.config().resolve("sonidos.properties");
    private static final Map<Integer, AudioClip> clips = new ConcurrentHashMap<>();
    private static final Set<Integer> missing = ConcurrentHashMap.newKeySet();
    private static volatile Properties settings;
    private static volatile boolean broken = false;

    private Sounds() {
    }

    /** Suena {@code sfx} (desde cualquier hilo). */
    public static void play(Sfx sfx) {
        if (!Platform.isFxApplicationThread()) {
            try {
                Platform.runLater(() -> play(sfx));
            } catch (IllegalStateException e) {
                // sin JavaFX (servidor, pruebas): sin sonido
            }
            return;
        }
        if (!isEnabled()) return;
        AudioClip clip = clip(fileFor(sfx));
        if (clip != null) clip.play(volume());
    }

    /** Corta {@code sfx} si está sonando (p. ej. los pitidos de evolución al aparecer la nueva forma). */
    public static void stop(Sfx sfx) {
        AudioClip clip = clips.get(fileFor(sfx));
        if (clip != null) clip.stop();
    }

    /** Carga los archivos en segundo plano al arrancar, para que el primer sonido no se demore. */
    public static void preload() {
        Thread t = new Thread(() -> {
            for (Sfx sfx : Sfx.values()) clip(fileFor(sfx));
        }, "precarga-sonidos");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(settings().getProperty("activado", "true").trim());
    }

    /** Activa o silencia los sonidos (botón del menú) y lo guarda. */
    public static void setEnabled(boolean on) {
        settings().setProperty("activado", String.valueOf(on));
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(CONFIG, StandardCharsets.UTF_8));
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().startsWith("activado=")) {
                    lines.set(i, "activado=" + on);
                    found = true;
                }
            }
            if (!found) lines.add("activado=" + on);
            Files.write(CONFIG, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[SONIDO] No se pudo guardar " + CONFIG + ": " + e.getMessage());
        }
    }

    private static double volume() {
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(settings().getProperty("volumen", "0.6").trim())));
        } catch (NumberFormatException e) {
            return 0.6;
        }
    }

    private static int fileFor(Sfx sfx) {
        try {
            return Integer.parseInt(settings().getProperty(sfx.key, String.valueOf(sfx.defaultFile)).trim());
        } catch (NumberFormatException e) {
            return sfx.defaultFile;
        }
    }

    private static AudioClip clip(int number) {
        if (broken || number <= 0 || missing.contains(number)) return null;
        AudioClip clip = clips.get(number);
        if (clip != null) return clip;
        Path folder = AppPaths.sounds();
        Path file = folder.resolve(number + ".mp3");
        if (!Files.isRegularFile(file)) file = folder.resolve(number + ".wav");
        if (!Files.isRegularFile(file)) {
            if (missing.add(number)) System.out.println("[SONIDO] No está " + folder.resolve(number + ".mp3") + " (no suena).");
            return null;
        }
        try {
            clip = new AudioClip(file.toUri().toString());
            clips.put(number, clip);
            return clip;
        } catch (Throwable e) { // sin audio en esta PC: el juego sigue, en silencio
            broken = true;
            System.out.println("[SONIDO] No se pueden reproducir sonidos: " + e);
            return null;
        }
    }

    private static Properties settings() {
        Properties s = settings;
        if (s != null) return s;
        synchronized (Sounds.class) {
            if (settings != null) return settings;
            if (!Files.exists(CONFIG)) writeDefaults();
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.out.println("[SONIDO] No se pudo leer " + CONFIG + ": " + e.getMessage());
            }
            settings = p;
            return p;
        }
    }

    private static void writeDefaults() {
        StringBuilder text = new StringBuilder("""
                # Efectos de sonido (archivos N.mp3 de la carpeta DigimonProjectData\\sounds).
                # activado: también se cambia desde el menú del Digimon (botón del parlante).
                activado=true
                # Volumen de 0 a 1.
                volumen=0.6

                # Qué archivo suena en cada momento (el número N de N.mp3). 0 = ese momento en silencio.
                """);
        for (Sfx sfx : Sfx.values()) {
            text.append("# ").append(sfx.description).append('\n')
                    .append(sfx.key).append('=').append(sfx.defaultFile).append('\n');
        }
        try {
            Files.createDirectories(CONFIG.getParent());
            Files.writeString(CONFIG, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[SONIDO] No se pudo crear " + CONFIG + ": " + e.getMessage());
        }
    }
}
