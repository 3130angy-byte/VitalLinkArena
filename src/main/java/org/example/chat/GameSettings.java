package org.example.chat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;

/**
 * Ajustes del juego, en DigimonProjectData\config\vitallink.properties (editable a mano). Se relee en cada uso:
 * cambiar el archivo aplica sin reiniciar.
 */
public final class GameSettings {

    private static final Path FILE = AppPaths.config().resolve("vitallink.properties");

    private static final String RIVAL_FOLDER = "batalla.carpetaRivales";
    private static final String ONLINE_HOST = "online.servidor";
    private static final String ONLINE_PORT = "online.puerto";
    private static final String PLAYER_NAME = "online.nombreJugador";
    private static final int DEFAULT_ONLINE_PORT = 7777;
    /**
     * 127.0.0.1 = esta PC. El paquete descargable arranca con -Ddigimon.servidor=100.x (un ejemplo: cada jugador
     * escribe la IP de Tailscale del anfitrión en la pantalla de inicio).
     */
    private static final String DEFAULT_ONLINE_HOST = System.getProperty("digimon.servidor", "127.0.0.1");

    private GameSettings() {}

    /** Carpeta con DIM cards normales para los rivales de Batalla aleatoria y ARENA (ver RivalDimPool). */
    public static Optional<Path> rivalDimFolder() {
        String configured = load().getProperty(RIVAL_FOLDER, "").trim();
        return configured.isEmpty() ? Optional.empty() : Optional.of(Paths.get(configured));
    }

    /** Servidor del VS Online: 127.0.0.1 si corre en esta PC, o la IP de Tailscale (100.x.x.x) del anfitrión. */
    public static String onlineHost() {
        if (sessionHost != null) return sessionHost;
        String configured = load().getProperty(ONLINE_HOST, "").trim();
        return configured.isEmpty() ? DEFAULT_ONLINE_HOST : configured;
    }

    public static int onlinePort() {
        try {
            return Integer.parseInt(load().getProperty(ONLINE_PORT, String.valueOf(DEFAULT_ONLINE_PORT)).trim());
        } catch (NumberFormatException e) {
            return DEFAULT_ONLINE_PORT;
        }
    }

    /** Nombre del jugador en la sala. Por defecto, el usuario de Windows. */
    public static String playerName() {
        if (sessionName != null) return sessionName;
        String configured = load().getProperty(PLAYER_NAME, "").trim();
        return configured.isEmpty() ? System.getProperty("user.name", "Jugador") : configured;
    }

    /** Nombre escrito en la pantalla de inicio la vez anterior; vacío si nunca se escribió. */
    public static String savedPlayerName() { return load().getProperty(PLAYER_NAME, "").trim(); }

    /**
     * Nombre y servidor escritos en la pantalla de inicio de ESTE programa (en memoria): con dos programas
     * abiertos en la misma PC, el archivo es uno solo, y cada programa debe usar el suyo. El archivo solo sirve
     * para precargarlos la próxima vez.
     */
    private static volatile String sessionName, sessionHost;

    /** Guarda el nombre de usuario y el servidor de la pantalla de inicio sin tocar los demás ajustes. */
    public static void saveStartScreen(String name, String host) {
        if (name != null && !name.isBlank()) sessionName = name.trim();
        if (host != null && !host.isBlank()) sessionHost = host.trim();
        Properties p = load();
        p.setProperty(PLAYER_NAME, name == null ? "" : name.trim());
        if (host != null) p.setProperty(ONLINE_HOST, host.trim());
        write(p);
    }

    /** Carpeta de rivales elegida al pedir Batalla aleatoria o en LAB > RIVALES. */
    public static void saveRivalFolder(String folder) {
        Properties p = load();
        p.setProperty(RIVAL_FOLDER, folder == null ? "" : folder.trim());
        write(p);
    }

    private static Properties load() {
        Properties p = new Properties();
        if (!Files.exists(FILE)) write(new Properties());
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            System.out.println("[AJUSTES] No se pudo leer " + FILE + ": " + e.getMessage());
        }
        return p;
    }

    private static void write(Properties p) {
        // Properties escapa las barras invertidas: "D:\Rivales" se guarda como "D:\\Rivales".
        String rivals = p.getProperty(RIVAL_FOLDER, "").trim().replace("\\", "\\\\");
        String text = """
                # Ajustes de VitalLink Arena -- puedes editar este archivo.
                # Carpeta con DIM cards normales (.bin) que aportan los rivales de Batalla aleatoria y ARENA.
                batalla.carpetaRivales=%s
                # VS Online: servidor (127.0.0.1 = esta PC; o la IP de Tailscale 100.x.x.x del anfitrión) y puerto.
                online.servidor=%s
                online.puerto=%s
                # Tu nombre en la sala del VS Online. Vacío = tu usuario de Windows.
                online.nombreJugador=%s
                """.formatted(rivals, p.getProperty(ONLINE_HOST, "").trim(),
                p.getProperty(ONLINE_PORT, String.valueOf(DEFAULT_ONLINE_PORT)).trim(),
                p.getProperty(PLAYER_NAME, "").trim());
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            w.write(text);
        } catch (IOException e) {
            System.out.println("[AJUSTES] No se pudo guardar " + FILE + ": " + e.getMessage());
        }
    }
}
