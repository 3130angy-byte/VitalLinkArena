package org.example.online.client;

import org.example.chat.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ranking (v13): "un Digimon vivo sale al morir" (decisión del usuario). Al morir se anota aquí su uid y, cada vez
 * que se entra a una sala, se le avisa a ESE servidor ("rankGone"); el servidor solo borra los del mismo dueño.
 * Se recuerdan 31 días (después la tabla ya se reinició igual), así también se entera el servidor de otro anfitrión.
 * Archivo: DigimonProjectData\config\ranking-bajas.txt (una línea "uid<TAB>fecha").
 */
public final class RankingGone {

    private static final int KEEP_DAYS = 31;

    private RankingGone() {}

    private static Path file() {
        return AppPaths.config().resolve("ranking-bajas.txt");
    }

    /** Murió este Digimon (su vida): sale del ranking la próxima vez que entres a una sala. */
    public static synchronized void note(String uid) {
        if (uid == null || uid.isBlank()) return;
        List<String> lines = read();
        lines.add(uid.strip() + "\t" + LocalDate.now());
        write(lines);
    }

    /** Los uids que todavía hay que avisar (los de más de 31 días se olvidan). */
    public static synchronized Set<String> pending() {
        List<String> lines = read();
        List<String> kept = new ArrayList<>();
        Set<String> uids = new LinkedHashSet<>();
        LocalDate limit = LocalDate.now().minusDays(KEEP_DAYS);
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 2) continue;
            try {
                if (LocalDate.parse(parts[1].trim()).isBefore(limit)) continue;
            } catch (RuntimeException e) {
                continue;
            }
            kept.add(line);
            uids.add(parts[0].trim());
        }
        if (kept.size() != lines.size()) write(kept);
        return uids;
    }

    private static List<String> read() {
        try {
            return Files.exists(file()) ? new ArrayList<>(Files.readAllLines(file(), StandardCharsets.UTF_8)) : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void write(List<String> lines) {
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[RANKING] No se pudo guardar " + file() + ": " + e.getMessage());
        }
    }
}
