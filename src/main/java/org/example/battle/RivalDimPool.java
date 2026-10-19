package org.example.battle;

import org.example.chat.GameSettings;
import org.example.dim.DimVPetData;
import org.example.dim.VsDimReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rivales para Batalla aleatoria: DIM cards NORMALES de la carpeta elegida
 * en Ajustes (batalla.carpetaRivales). Hace falta porque en 0.0.3 los
 * Digimon llegan solo por VS DIM, y una VS DIM trae únicamente los sprites
 * de su propio Digimon. Estas DIM no se crían ni aparecen en el escritorio.
 *
 * Cada DIM leída se guarda en memoria para no releer 4MB en cada pelea.
 */
public final class RivalDimPool {

    private static final Map<Path, DimVPetData> LOADED = new ConcurrentHashMap<>();
    /** Archivos que no son DIM cards legibles (VS DIM, BE Memory...): no se vuelven a intentar. */
    private static final Set<Path> SKIPPED = ConcurrentHashMap.newKeySet();
    /** Se revisan también subcarpetas (p. ej. elegir "DIMCARDS" con una carpeta por tarjeta). */
    private static final int MAX_DEPTH = 3;

    private RivalDimPool() {}

    /** Una DIM card normal al azar de la carpeta (o sus subcarpetas), o vacío si no hay carpeta o ninguna sirve. */
    public static Optional<DimVPetData> pickRandomCard() {
        Optional<Path> folder = GameSettings.rivalDimFolder();
        if (folder.isEmpty() || !Files.isDirectory(folder.get())) return Optional.empty();

        List<Path> candidates;
        try (Stream<Path> files = Files.walk(folder.get(), MAX_DEPTH)) {
            candidates = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".bin"))
                    .filter(p -> !SKIPPED.contains(p))
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            System.out.println("[RIVALES] No se pudo leer la carpeta: " + e.getMessage());
            return Optional.empty();
        }
        Collections.shuffle(candidates);

        for (Path path : candidates) {
            DimVPetData cached = LOADED.get(path);
            if (cached != null) return Optional.of(cached);
            if (VsDimReader.isVsDimFile(path)) { // una VS DIM no trae rivales con sprites
                SKIPPED.add(path);
                continue;
            }
            try {
                DimVPetData card = DimVPetData.load(path, 0);
                LOADED.put(path, card);
                System.out.println("[RIVALES] DIM rival: " + path.getFileName());
                return Optional.of(card);
            } catch (Exception e) {
                SKIPPED.add(path);
                System.out.println("[RIVALES] Se omite " + path.getFileName() + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }
}
