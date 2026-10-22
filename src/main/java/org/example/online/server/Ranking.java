package org.example.online.server;

import org.example.chat.AppPaths;
import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RANKING del VS Online (protocolo v13, conjunto 4 de la actualización conjunta; decisiones del usuario,
 * 2026-10-18): victorias de cada DIGIMON con el nombre de su dueño, UNO por tipo de pelea (Original, Libre y
 * ARENA 2 vs 2; cuentan los retos del NPC y los combates de torneo).
 *  - Un Digimon que no entra a la sala en 2 semanas sale (configurable: ranking.diasSinEntrar).
 *  - TODO se reinicia cada mes (el mes anterior queda guardado aparte, como recuerdo).
 *  - Las COPIAS del LAB figuran solo ese DÍA: al día siguiente salen, sin importar el puesto.
 *  - Un Digimon vivo sale al MORIR (la 0.0.4 lo avisa con "rankGone" la próxima vez que entra a una sala).
 * En la ARENA la victoria es del puesto 1 del equipo (el Digimon que te representa en la sala).
 *
 * Cada Digimon se reconoce por su "uid" (la cápsula en la 0.0.3, la vida o la copia en la 0.0.4); un cliente
 * viejo sin uid queda como "dueño + especie". Se guarda en la config común (ranking.json), así el servidor de
 * cualquiera de las dos versiones del anfitrión lleva la misma tabla.
 */
final class Ranking {

    /** Filas que se muestran (más las tuyas, si están más abajo). */
    static final int TOP = 20;

    private static final class Entry {
        String key, uid, owner, species;
        boolean copy;
        int wins, losses;
        /** Último día en que entró a la sala o peleó (epoch day). */
        long lastSeen;

        JSONObject toJson() {
            return new JSONObject().put("key", key).put("uid", uid).put("owner", owner).put("species", species)
                    .put("copy", copy).put("wins", wins).put("losses", losses).put("lastSeen", lastSeen);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.key = o.getString("key");
            e.uid = o.optString("uid", "");
            e.owner = o.optString("owner", "?");
            e.species = o.optString("species", "Digimon");
            e.copy = o.optBoolean("copy");
            e.wins = o.optInt("wins");
            e.losses = o.optInt("losses");
            e.lastSeen = o.optLong("lastSeen");
            return e;
        }
    }

    private final Path file;
    private final int daysWithoutPlaying;
    /** "2026-10": el mes de la tabla actual. */
    private String month;
    /** modo -> clave -> entrada. */
    private final Map<String, Map<String, Entry>> tables = new HashMap<>();

    Ranking(ServerConfig config) {
        String override = System.getProperty("vs.ranking.archivo"); // pruebas: nunca tocar la tabla del anfitrión
        this.file = override != null ? Path.of(override) : AppPaths.sharedConfig().resolve("ranking.json");
        this.daysWithoutPlaying = config.rankingDays;
        for (String mode : modes()) tables.put(mode, new LinkedHashMap<>());
        load();
    }

    static List<String> modes() {
        return List.of(Protocol.MODE_ORIGINAL, Protocol.MODE_FREE, Protocol.MODE_ARENA);
    }

    /** Hoy (las pruebas lo fijan con -Dvs.ranking.hoy=AAAA-MM-DD). */
    private static LocalDate today() {
        String forced = System.getProperty("vs.ranking.hoy");
        return forced != null ? LocalDate.parse(forced.trim()) : LocalDate.now();
    }

    private static String monthOf(LocalDate day) {
        return day.toString().substring(0, 7);
    }

    // ---------------------------------------------------------------- lo que pasa en la sala

    /** Una pelea terminada con ganador (los empates no cuentan). */
    synchronized void record(ClientConnection winner, ClientConnection loser, String mode) {
        Map<String, Entry> table = tables.get(mode);
        if (table == null || winner == null || loser == null || winner.digimon == null || loser.digimon == null) return;
        purge();
        long day = today().toEpochDay();
        Entry w = entryFor(table, winner, day), l = entryFor(table, loser, day);
        w.wins++;
        l.losses++;
        save();
        VsServer.log("Ranking " + Protocol.modeName(mode) + ": " + w.species + " de " + w.owner + " " + w.wins + " victorias.");
    }

    /** Entró a la sala con este Digimon: sigue vigente (cuenta para las 2 semanas). */
    synchronized void seen(ClientConnection c) {
        if (c.digimon == null) return;
        String key = keyOf(c);
        long day = today().toEpochDay();
        boolean changed = false;
        for (Map<String, Entry> table : tables.values()) {
            Entry e = table.get(key);
            if (e != null && e.lastSeen != day) {
                e.lastSeen = day;
                e.species = c.digimon.optString("species", e.species); // pudo evolucionar
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Murieron estos Digimon (los avisa su dueño): salen de todas las tablas. Solo los de ESE dueño. */
    synchronized void gone(ClientConnection c, Collection<String> uids) {
        boolean changed = false;
        for (Map<String, Entry> table : tables.values()) {
            changed |= table.values().removeIf(e -> !e.uid.isEmpty() && uids.contains(e.uid) && e.owner.equalsIgnoreCase(c.name));
        }
        if (changed) {
            save();
            VsServer.log("Ranking: " + c.name + " avisó que " + uids.size() + " Digimon ya no están (murieron).");
        }
    }

    /** La tabla de un modo: las primeras TOP filas y, si quedaron más abajo, las de quien pregunta. */
    synchronized JSONObject list(ClientConnection asker, String mode) {
        purge(); // primero: un mes nuevo cambia el mes que se informa
        Map<String, Entry> table = tables.get(mode);
        JSONObject reply = Protocol.msg("rankList").put("mode", mode).put("month", month)
                .put("days", daysWithoutPlaying);
        JSONArray rows = new JSONArray();
        if (table != null) {
            List<Entry> sorted = sorted(table.values());
            String mine = asker.digimon == null ? null : keyOf(asker);
            for (int i = 0; i < sorted.size(); i++) {
                Entry e = sorted.get(i);
                boolean own = e.owner.equalsIgnoreCase(asker.name);
                if (i >= TOP && !own) continue;
                rows.put(new JSONObject().put("pos", i + 1).put("species", e.species).put("owner", e.owner)
                        .put("wins", e.wins).put("losses", e.losses).put("copy", e.copy)
                        .put("mine", own).put("current", e.key.equals(mine)));
            }
        }
        return reply.put("rows", rows);
    }

    // ---------------------------------------------------------------- reglas

    /** Mes nuevo = tabla nueva; copias de otro día y Digimon que no entran hace 2 semanas, fuera. */
    private void purge() {
        LocalDate now = today();
        boolean changed = false;
        if (!monthOf(now).equals(month)) {
            archive();
            for (Map<String, Entry> table : tables.values()) table.clear();
            VsServer.log("Ranking: mes nuevo (" + monthOf(now) + "), la tabla empieza de cero.");
            month = monthOf(now);
            changed = true;
        }
        long day = now.toEpochDay();
        for (Map<String, Entry> table : tables.values()) {
            changed |= table.values().removeIf(e -> (e.copy && e.lastSeen != day) || day - e.lastSeen >= daysWithoutPlaying);
        }
        if (changed) save();
    }

    private static List<Entry> sorted(Collection<Entry> entries) {
        List<Entry> list = new ArrayList<>(entries);
        // Más victorias primero; con las mismas, menos derrotas; luego el que peleó más recientemente.
        list.sort(Comparator.comparingInt((Entry e) -> -e.wins).thenComparingInt(e -> e.losses)
                .thenComparingLong(e -> -e.lastSeen));
        return list;
    }

    private Entry entryFor(Map<String, Entry> table, ClientConnection c, long day) {
        String key = keyOf(c);
        Entry e = table.computeIfAbsent(key, k -> {
            Entry fresh = new Entry();
            fresh.key = k;
            fresh.uid = c.uid;
            return fresh;
        });
        e.owner = c.name;
        e.species = c.digimon.optString("species", "Digimon");
        e.copy = VsServer.isCopy(c.digimon);
        e.lastSeen = day;
        return e;
    }

    /** El Digimon por su uid; sin uid (cliente viejo), dueño + especie. */
    private static String keyOf(ClientConnection c) {
        if (!c.uid.isEmpty()) return "uid:" + c.uid;
        String species = c.digimon == null ? "Digimon" : c.digimon.optString("species", "Digimon");
        return "nombre:" + c.name.toLowerCase() + "|" + species.toLowerCase();
    }

    // ---------------------------------------------------------------- archivo

    private void load() {
        month = monthOf(today());
        if (!Files.exists(file)) return;
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            month = root.optString("month", month);
            JSONObject t = root.optJSONObject("tables");
            if (t != null) {
                for (String mode : modes()) {
                    JSONArray arr = t.optJSONArray(mode);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        Entry e = Entry.fromJson(arr.getJSONObject(i));
                        tables.get(mode).put(e.key, e);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            VsServer.log("No se pudo leer el ranking (" + file + "): " + e.getMessage() + ". Empieza vacío.");
        }
    }

    private void save() {
        JSONObject t = new JSONObject();
        for (String mode : modes()) {
            JSONArray arr = new JSONArray();
            for (Entry e : tables.get(mode).values()) arr.put(e.toJson());
            t.put(mode, arr);
        }
        String text = new JSONObject().put("month", month).put("tables", t).toString(1);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            VsServer.log("No se pudo guardar el ranking: " + e.getMessage());
        }
    }

    /** La tabla del mes que termina queda como recuerdo: ranking-AAAA-MM.json. */
    private void archive() {
        if (month == null || !Files.exists(file)) return;
        boolean empty = tables.values().stream().allMatch(Map::isEmpty);
        if (empty) return;
        try {
            Files.copy(file, file.resolveSibling("ranking-" + month + ".json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            VsServer.log("No se pudo guardar el ranking de " + month + ": " + e.getMessage());
        }
    }

    /** Uids que se aceptan: letras, números, guiones y ":" (nunca rutas ni texto raro). */
    static String cleanUid(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        return s.length() <= 64 && s.matches("[A-Za-z0-9:_-]+") ? s : "";
    }

    /** Lista de uids del mensaje "rankGone" (como mucho 50, cada uno limpiado). */
    static Set<String> uids(JSONArray arr) {
        Set<String> out = new java.util.HashSet<>();
        if (arr == null) return out;
        for (int i = 0; i < Math.min(arr.length(), 50); i++) {
            String u = cleanUid(arr.optString(i, ""));
            if (!u.isEmpty()) out.add(u);
        }
        return out;
    }
}
