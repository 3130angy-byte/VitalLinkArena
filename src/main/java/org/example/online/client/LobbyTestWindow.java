package org.example.online.client;

import javafx.application.Application;
import javafx.stage.Stage;

import org.example.dim.VsDimData;
import org.example.dim.VsDimReader;
import org.example.online.Protocol;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lanzador de PRUEBA de la sala del VS Online, sin pasar por el programa
 * principal (ahí se entra por Batalla > VS Online). Abre una o varias
 * LobbyWindow en el mismo proceso.
 *
 * Argumentos (todos opcionales):
 *   --host=127.0.0.1  --port=7777  --name=Fernando  --ventanas=2
 *   --vsdim="ruta1.bin;ruta2.bin"  --especie="MagnaKidmon;Dynasmon"  (una por ventana)
 * Con --ventanas=2 abre dos jugadores: la forma más rápida de probar que
 * se ven moverse y chatean.
 */
public class LobbyTestWindow extends Application {

    @Override
    public void start(Stage primaryStage) {
        Map<String, String> args = parseArgs(getParameters().getRaw());
        String host = args.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(args.getOrDefault("port", String.valueOf(Protocol.DEFAULT_PORT)));
        int windows = Math.max(1, Math.min(4, Integer.parseInt(args.getOrDefault("ventanas", "1"))));
        String baseName = args.get("name");
        // --vsdim=ruta1;ruta2  y  --especie=MagnaKidmon;Dynasmon  (una por ventana, en orden).
        String[] vsDims = args.getOrDefault("vsdim", "").split(";");
        String[] species = args.getOrDefault("especie", "").split(";");

        for (int i = 0; i < windows; i++) {
            String name = baseName != null
                    ? (windows > 1 ? baseName + (i + 1) : baseName)
                    : "Prueba" + (int) (Math.random() * 900 + 100);
            Stage stage = i == 0 ? primaryStage : new Stage();
            String dimPath = i < vsDims.length ? vsDims[i].trim() : "";
            String sp = i < species.length ? species[i].trim() : "";
            new LobbyWindow(stage, host, port, name, i, loadDigimon(dimPath, sp),
                    "VS Online (prueba) - " + name, null).open();
        }
    }

    /** Mensaje "digimon" desde una VS DIM, o null si no se indicó o no se pudo leer. */
    private static JSONObject loadDigimon(String path, String species) {
        if (path.isEmpty()) return null;
        try {
            VsDimData vs = VsDimReader.read(Path.of(path));
            // Especie: la indicada; si no, "Digimon" (nunca el nombre del archivo).
            return LobbyDigimon.payloadFrom(vs, species.isEmpty() ? "Digimon" : species);
        } catch (Exception e) {
            System.out.println("No se pudo leer la VS DIM " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static Map<String, String> parseArgs(List<String> raw) {
        Map<String, String> map = new HashMap<>();
        for (String a : raw) {
            if (a.startsWith("--") && a.contains("=")) {
                map.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
            }
        }
        return map;
    }
}
