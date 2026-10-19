package org.example.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Toda ruta de datos generados por el programa vive bajo D:, nunca C: (en
 * la laptop del usuario C: casi no tiene espacio). En otra PC sin disco D:
 * se usa la carpeta del usuario de Windows.
 */
public final class AppPaths {

    private static final Path BASE = Files.isDirectory(Paths.get("D:\\"))
            ? Paths.get("D:", "DigimonProjectData")
            : Paths.get(System.getProperty("user.home"), "DigimonProjectData");

    private AppPaths() {}

    public static Path base() { return ensure(BASE); }
    public static Path config() { return ensure(BASE.resolve("config")); }
    /**
     * La carpeta config COMÚN a todas las versiones (en la 0.0.3 es la misma config): ahí viven los permisos del
     * servidor (lista de acceso, solicitudes, señal de emergencia), que comparte con la 0.0.4 (2026-10-16).
     */
    public static Path sharedConfig() { return config(); }
    /** VS DIM de vuelta al Vital Bracelet (al retirar un Digimon). */
    public static Path returns() { return ensure(BASE.resolve("devoluciones")); }
    /** Laboratorio: cápsulas (copias de VS DIM), Digidex e historial de batallas. */
    public static Path lab() { return ensure(BASE.resolve("laboratorio")); }
    /** Efectos de sonido del usuario (N.mp3); no vienen con el programa y la carpeta no se crea sola. */
    public static Path sounds() { return BASE.resolve("sounds"); }

    private static Path ensure(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            System.out.println("No se pudo crear " + path + ": " + e.getMessage());
        }
        return path;
    }
}