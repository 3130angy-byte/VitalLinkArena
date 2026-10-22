import org.example.chat.PhraseBank;
import org.example.chat.PhrasePicker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Muestras del banco de frases para revisarlo: 50 reacciones al azar por perfil (atributo x etapa x
 * Activity Type), armadas con el MISMO código que usa el programa (PhrasePicker). Todas llevan estilo
 * para ver cómo combina (en el juego sale con la probabilidad de su Activity Type).
 *
 * Necesita el programa compilado (.\gradlew.bat compileJava):
 *   java -Dfile.encoding=UTF-8 -cp build/classes/java/main tools/GenerarMuestrasBanco.java banco-frases-borrador banco-frases-borrador/muestras
 *
 * Mismas muestras cada vez (semilla fija por perfil): si cambias el banco, vuelve a generarlas.
 */
public class GenerarMuestrasBanco {
    static final int PER_PROFILE = 50;
    static final String[] ATTR_NAMES = {"vacuna", "data", "virus", "free"};
    static final int[] ATTR_CODES = {3, 2, 1, 4};
    static final String[] STAGE_NAMES = {"Child", "Adult y Perfect", "Ultimate"};
    static final int[] STAGE_CODES = {2, 3, 5};
    static final String[] TYPES = {"estoico", "activo", "normal", "interior", "perezoso"};
    static final Map<String, String> VARS = Map.of("jugador", "Sam", "rival", "Dynasmon", "companero", "Kumamon",
            "vv", "200");

    public static void main(String[] args) throws IOException {
        PhraseBank bank = PhraseBank.fromFolder(Path.of(args[0]));
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        List<String> events = bank.eventNames();

        for (int a = 0; a < ATTR_NAMES.length; a++) {
            StringBuilder sb = new StringBuilder();
            sb.append("# MUESTRAS DEL BANCO — ").append(ATTR_NAMES[a].toUpperCase()).append(" (generado, no editar: se rehace solo)\n")
              .append("# 50 reacciones al azar por perfil, con el mismo código del programa (sin aperturas ni cierres por\n")
              .append("# Activity Type: se quitaron el 2026-10-22; el estoico solo prefiere las frases más cortas).\n")
              .append("# Cada perfil simula una vida: la 1.ª vez de un evento sale su frase de \"primera vez\" (marcada).\n")
              .append("# Variables de ejemplo: ").append(new TreeMap<>(VARS)).append("\n");
            for (int s = 0; s < STAGE_NAMES.length; s++) {
                for (int t = 0; t < TYPES.length; t++) {
                    sb.append("\n=== ").append(ATTR_NAMES[a].toUpperCase()).append(" · ").append(STAGE_NAMES[s].toUpperCase())
                      .append(" · ").append(TYPES[t].toUpperCase()).append(" ===\n");
                    Random rnd = new Random((ATTR_NAMES[a] + STAGE_NAMES[s] + TYPES[t]).hashCode());
                    PhrasePicker picker = new PhrasePicker(bank, ATTR_CODES[a], STAGE_CODES[s], t, rnd, PhrasePicker.inMemory());
                    int n = 0;
                    for (int guard = 0; n < PER_PROFILE && guard < 5000; guard++) {
                        String event = events.get(rnd.nextInt(events.size()));
                        var r = picker.pickDetailed(event, VARS, false);
                        if (r.isEmpty()) continue;
                        n++;
                        String label = event + (r.get().firstTime() ? " · 1ª vez" : "");
                        sb.append(String.format("%2d. %-26s %s%n", n, "[" + label + "]", r.get().text()));
                    }
                }
            }
            Files.writeString(out.resolve("muestras-" + ATTR_NAMES[a] + ".txt"), sb.toString(), StandardCharsets.UTF_8);
        }
        System.out.println("Listo: " + out.toAbsolutePath());
    }
}
