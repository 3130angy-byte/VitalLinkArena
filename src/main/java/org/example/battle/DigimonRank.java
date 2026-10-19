package org.example.battle;

import org.example.chat.AppPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Rango C/B/A/S por puntos (campo [6] de la VS DIM: Power Trophies con el
 * mod Digimon Link; trofeos de evolución en un VB original, sin confirmar).
 * Umbrales decididos por el usuario (2026-09-26): C desde 10, B desde 30,
 * A desde 70, S desde 120; con menos de 10, sin rango ("-").
 *
 * Una sola tabla para el servidor (ServerConfig) y el panel "Digimon": los
 * umbrales se leen de D:\DigimonProjectData\config\vs-server.properties
 * (lo crea el servidor con los valores por defecto). Ojo: el panel usa el
 * archivo de ESTA PC; en una sala ajena manda el del anfitrión.
 */
public final class DigimonRank {

    /** Sin rango: menos puntos que el umbral de C. */
    public static final String NO_RANK = "-";
    public static final Path FILE = AppPaths.config().resolve("vs-server.properties");

    public final int c, b, a, s;

    private DigimonRank(Properties p) {
        c = intOf(p, "rango.C.desde", 10);
        b = intOf(p, "rango.B.desde", 30);
        a = intOf(p, "rango.A.desde", 70);
        s = intOf(p, "rango.S.desde", PowerTrophyBonus.MAX_TROPHIES);
    }

    /** Desde propiedades ya leídas (el servidor lee el archivo una vez para todos sus ajustes). */
    public static DigimonRank from(Properties p) {
        return new DigimonRank(p);
    }

    /** Lee el archivo; si no existe o falla, valores por defecto. */
    public static DigimonRank load() {
        Properties p = new Properties();
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {
                // valores por defecto
            }
        }
        return new DigimonRank(p);
    }

    public String rankFor(int points) {
        if (points >= s) return "S";
        if (points >= a) return "A";
        if (points >= b) return "B";
        if (points >= c) return "C";
        return NO_RANK;
    }

    /** Puntos que pide el rango siguiente, o -1 si ya es S. */
    public int nextThreshold(int points) {
        if (points < c) return c;
        if (points < b) return b;
        if (points < a) return a;
        if (points < s) return s;
        return -1;
    }

    private static int intOf(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
