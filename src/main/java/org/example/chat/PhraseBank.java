package org.example.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Banco de frases del Digimon (decisión del usuario, 2026-10-06): sus reacciones a cada evento (ganar,
 * perder, aparecer, ir a la sala...) salen de aquí. Se edita
 * en la carpeta banco-frases-borrador (formato en su LEEME.txt) y Gradle lo copia dentro del
 * programa (recursos /banco). Solo lee: las reglas para elegir están en PhrasePicker.
 */
public final class PhraseBank {

    public static final String[] FILES = {"peleas.txt", "compania.txt"};
    /** "fijo" = sin aperturas ni cierres del Activity Type (no hay estilo para ese tono): solo atributo y etapa. */
    private static final Pattern TONE = Pattern.compile("Tono: (bueno|malo|neutro|fijo)");

    /** Un evento: tono (para el estilo) y sus frases por sección ("nino", "maduro", "primera vez nino"...). */
    public record Event(String name, String tone, Map<String, List<Phrase>> sections) {}

    /** Una frase: atributo ("vacuna", "data", "virus", "free" o "todos") y texto con variables. */
    public record Phrase(String attr, String text) {}

    private final Map<String, Event> events;
    /** "activo|maduro|bueno|antes" -> aperturas o cierres de estilo.txt. */
    private final Map<String, List<String>> style;

    private PhraseBank(Map<String, Event> events, Map<String, List<String>> style) {
        this.events = events;
        this.style = style;
    }

    private static PhraseBank packaged;

    /** El banco que viaja dentro del programa. Vacío (sin frases) si falta: se usan los textos fijos. */
    public static synchronized PhraseBank packaged() {
        if (packaged == null) {
            try {
                Map<String, Event> ev = new LinkedHashMap<>();
                for (String f : FILES) {
                    try (Reader r = resource(f)) {
                        if (r != null) readEvents(new BufferedReader(r), ev);
                    }
                }
                Map<String, List<String>> st = new HashMap<>();
                try (Reader r = resource("estilo.txt")) {
                    if (r != null) readStyle(new BufferedReader(r), st);
                }
                packaged = new PhraseBank(ev, st);
                System.out.println("[FRASES] Banco cargado: " + ev.size() + " eventos.");
            } catch (IOException e) {
                System.out.println("[FRASES] No se pudo leer el banco: " + e.getMessage());
                packaged = new PhraseBank(Map.of(), Map.of());
            }
        }
        return packaged;
    }

    /** Desde una carpeta (las muestras de tools/GenerarMuestrasBanco leen el borrador directamente). */
    public static PhraseBank fromFolder(Path dir) throws IOException {
        Map<String, Event> ev = new LinkedHashMap<>();
        for (String f : FILES) {
            try (BufferedReader r = Files.newBufferedReader(dir.resolve(f), StandardCharsets.UTF_8)) {
                readEvents(r, ev);
            }
        }
        Map<String, List<String>> st = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(dir.resolve("estilo.txt"), StandardCharsets.UTF_8)) {
            readStyle(r, st);
        }
        return new PhraseBank(ev, st);
    }

    private static Reader resource(String file) {
        InputStream in = PhraseBank.class.getResourceAsStream("/banco/" + file);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    public Event event(String name) { return events.get(name); }
    public List<String> eventNames() { return List.copyOf(events.keySet()); }

    public List<String> style(String type, String stage, String tone, String position) {
        return style.getOrDefault(type + "|" + stage + "|" + tone + "|" + position, List.of());
    }

    private static void readEvents(BufferedReader r, Map<String, Event> out) throws IOException {
        String name = null, tone = "neutro", section = null;
        Map<String, List<Phrase>> sections = null;
        boolean header = false;
        for (String line; (line = r.readLine()) != null; ) {
            String l = line.strip();
            if (l.startsWith("=== ")) {
                if (name != null) out.put(name, new Event(name, tone, sections));
                name = l.replace("=", "").trim();
                tone = "neutro";
                section = null;
                sections = new LinkedHashMap<>();
                header = true;
                continue;
            }
            if (l.startsWith("#")) {
                if (header) {
                    Matcher m = TONE.matcher(l);
                    if (m.find()) tone = m.group(1);
                }
                header = false;
                continue;
            }
            header = false;
            if (l.startsWith("--- ")) {
                section = l.replaceAll("^-+\\s*|\\s*-+$", "").trim();
                continue;
            }
            int bar = l.indexOf(" | ");
            if (bar > 0 && name != null && section != null) {
                sections.computeIfAbsent(section, k -> new ArrayList<>())
                        .add(new Phrase(l.substring(0, bar).trim(), l.substring(bar + 3).trim()));
            }
        }
        if (name != null) out.put(name, new Event(name, tone, sections));
        out.replaceAll((k, e) -> new Event(e.name(), e.tone(), Collections.unmodifiableMap(e.sections())));
    }

    private static void readStyle(BufferedReader r, Map<String, List<String>> out) throws IOException {
        String type = null, stage = null;
        for (String line; (line = r.readLine()) != null; ) {
            String l = line.strip();
            if (l.startsWith("#")) continue;
            if (l.startsWith("=== ")) { type = l.replace("=", "").trim(); continue; }
            if (l.startsWith("--- ")) { stage = l.replaceAll("^-+\\s*|\\s*-+$", "").trim(); continue; }
            int bar = l.indexOf(" | ");
            if (bar > 0 && type != null && stage != null) {
                String[] key = l.substring(0, bar).trim().split("\\.");
                if (key.length != 2) continue;
                out.computeIfAbsent(type + "|" + stage + "|" + key[0] + "|" + key[1], k -> new ArrayList<>())
                        .add(l.substring(bar + 3).trim());
            }
        }
    }
}
