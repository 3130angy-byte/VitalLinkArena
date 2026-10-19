package org.example.lab;

import org.example.chat.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Historial de batallas del Laboratorio, GUARDADO EN DISCO (el récord de
 * DigimonProgress vive solo en memoria y se pierde al cerrar). Una línea por
 * batalla, separada por tabuladores, en
 * DigimonProjectData\laboratorio\historial.tsv:
 * fecha, Digimon, modo, rival, resultado, Vital Values estimados.
 */
public final class BattleHistory {

    public record Row(String when, String digimon, String mode, String rival, String result, String vitalValues) {}

    private BattleHistory() {}

    private static Path file() {
        return AppPaths.lab().resolve("historial.tsv");
    }

    public static synchronized void add(String digimon, String mode, String rival, String result, String vitalValues) {
        String line = String.join("\t", LocalDateTime.now().format(LabStorage.WHEN), clean(digimon), clean(mode),
                clean(rival), clean(result), clean(vitalValues)) + System.lineSeparator();
        try {
            Files.writeString(file(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[HISTORIAL] No se pudo guardar: " + e.getMessage());
        }
    }

    /** De la más nueva a la más vieja. */
    public static synchronized List<Row> rows() {
        List<Row> out = new ArrayList<>();
        if (!Files.exists(file())) return out;
        try {
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] c = line.split("\t", -1);
                if (c.length >= 6) out.add(new Row(c[0], c[1], c[2], c[3], c[4], c[5]));
            }
        } catch (IOException e) {
            System.out.println("[HISTORIAL] No se pudo leer: " + e.getMessage());
        }
        Collections.reverse(out);
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
