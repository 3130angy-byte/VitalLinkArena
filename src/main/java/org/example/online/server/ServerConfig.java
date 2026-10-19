package org.example.online.server;

import org.example.battle.DigimonRank;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Ajustes del servidor que el usuario debe poder cambiar sin recompilar
 * (decisión del usuario: lo provisional nunca fijo en código). Se leen de
 * D:\DigimonProjectData\config\vs-server.properties; si no existe, se crea
 * con los valores por defecto.
 *
 * Rango C/B/A/S por puntos: tabla compartida con el panel "Digimon"
 * (DigimonRank, mismos umbrales configurables).
 *
 * Límites anti-abuso (por conexión): cuántos mensajes de chat y de
 * movimiento se aceptan por segundo, y cuántas conexiones por IP.
 */
final class ServerConfig {

    /** Junto a los demás ajustes del programa (D:\DigimonProjectData\config), no en la carpeta del proyecto. */
    static final Path FILE = DigimonRank.FILE;

    final DigimonRank ranks;
    final int chatPerSecond;
    final int chatBurst;
    final int movesPerSecond;
    final int maxConnectionsPerIp;
    /** Torneos (protocolo v12): cuántos a la vez, segundos para decir "listo" y para volver tras un corte. */
    final int maxTournaments;
    final int readySeconds;
    final int reconnectSeconds;

    private ServerConfig(Properties p) {
        ranks = DigimonRank.from(p);
        chatPerSecond = intOf(p, "limite.chat.porSegundo", 1);
        chatBurst = intOf(p, "limite.chat.rafaga", 5);
        movesPerSecond = intOf(p, "limite.movimientos.porSegundo", 20);
        maxConnectionsPerIp = intOf(p, "limite.conexionesPorIp", 4);
        maxTournaments = Math.max(1, intOf(p, "torneo.maximoALaVez", 2));
        readySeconds = Math.max(15, intOf(p, "torneo.segundosParaListo", 60));
        reconnectSeconds = Math.max(0, intOf(p, "torneo.segundosParaVolver", 60));
    }

    static ServerConfig load() {
        Properties p = new Properties();
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                VsServer.log("No se pudo leer " + FILE.toAbsolutePath() + ", se usan valores por defecto: " + e.getMessage());
            }
        } else {
            writeDefaults();
        }
        return new ServerConfig(p);
    }

    /** Rango del Digimon según sus puntos (trofeos); configurable. */
    String rankFor(int points) {
        return ranks.rankFor(points);
    }

    private static void writeDefaults() {
        String text = """
                # Ajustes del servidor VS Online. Se leen al arrancar el servidor.
                #
                # Rango C/B/A/S por puntos (Power Trophies del mod o trofeos del VB original).
                # Con menos puntos que el rango C: sin rango.
                rango.C.desde=10
                rango.B.desde=30
                rango.A.desde=70
                rango.S.desde=120
                #
                # Límites anti-abuso por jugador.
                limite.chat.porSegundo=1
                limite.chat.rafaga=5
                limite.movimientos.porSegundo=20
                limite.conexionesPorIp=4
                #
                # Torneos: cuántos a la vez, segundos para aceptar cada combate ("listo") y para volver a la
                # sala tras un corte de conexión (pasado ese tiempo, queda descalificado de ESE torneo).
                torneo.maximoALaVez=2
                torneo.segundosParaListo=60
                torneo.segundosParaVolver=60
                """;
        try {
            Files.writeString(FILE, text, StandardCharsets.UTF_8);
            VsServer.log("Creado " + FILE.toAbsolutePath() + " con los valores por defecto.");
        } catch (IOException e) {
            VsServer.log("No se pudo crear " + FILE.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static int intOf(Properties p, String key, int def) {
        try {
            // -Dvs.<clave>=valor gana sobre el archivo (para pruebas, sin tocar el vs-server.properties del anfitrión).
            String override = System.getProperty("vs." + key);
            if (override != null) return Integer.parseInt(override.trim());
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
