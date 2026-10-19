package org.example.online;

import org.example.chat.AppPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Lista de ACCESO al servidor VS Online de esta PC (pedido del usuario, 0.0.3.z).
 *
 * Desde 2026-10-02 cada nombre queda ATADO A UNA CUENTA (capa 1): la cuenta de
 * Tailscale desde la que se conectó (la averigua el servidor, nadie la puede
 * escribir). Quien no está en la lista PIDE PERMISO al entrar (capa 2): queda
 * una SOLICITUD y espera; el anfitrión la acepta o la rechaza en Laboratorio >
 * ACCESO (o en la consola del servidor). Ya no se agregan nombres a mano.
 * Desde ESTA PC siempre se entra (es la PC del anfitrión).
 *
 * Archivos en ...\DigimonProjectData\config:
 *  - lista-acceso.txt: "anfitrion=Nombre" y una persona por línea:
 *    nombre[TAB]cuenta[TAB]cómo se muestra. Una línea con solo el nombre (lista
 *    vieja) no deja entrar sola: genera una solicitud que confirma su cuenta.
 *  - solicitudes-acceso.txt: las solicitudes pendientes (máx. 10, 1 por cuenta,
 *    vencen a las 24 h).
 * Los escribe el servidor (que puede ser "Servidor VS Online.exe", otro proceso)
 * y el Laboratorio: cada cambio va con un candado de archivo y se reemplaza el
 * archivo entero de una vez, así nadie lee una lista a medio escribir.
 * Retirar NO es un baneo: la persona puede volver a pedir permiso.
 */
public final class AccessList {

    public static final int MAX = 20;
    public static final int MAX_PENDING = 10;
    /** Lo que el servidor pone como cuenta de quien se conecta desde esta misma PC. */
    public static final String LOCAL = "local";
    private static final long REQUEST_TTL_MS = 24 * 60 * 60_000L;
    private static final String HOST_KEY = "anfitrion=";

    /** Una persona con permiso: su nombre de jugador y la cuenta a la que quedó atado ("" = lista vieja, sin cuenta). */
    public record Entry(String name, String identity, String label) {
        public boolean bound() { return !identity.isEmpty(); }
    }

    /** Alguien que pidió entrar. note = advertencia para el anfitrión ("" si nada raro). */
    public record Request(String id, String name, String identity, String label, String ip, long at, String note) {}

    /** Resultado de pedir permiso. */
    public enum Asked { NEW, UPDATED, FULL }

    private final String host;
    private final List<Entry> entries;

    private AccessList(String host, List<Entry> entries) {
        this.host = host;
        this.entries = entries;
    }

    /**
     * Los permisos son de la PC, no de una versión (pedido del usuario, 2026-10-16): la 0.0.3.z y la 0.0.4
     * comparten la lista, las solicitudes y el candado en la carpeta config COMÚN (DigimonProjectData\config).
     * Aceptas a alguien una vez y entra al servidor de cualquiera de las dos; las dos lo administran.
     */
    private static Path dir() {
        return AppPaths.sharedConfig();
    }

    public static Path file() {
        return dir().resolve("lista-acceso.txt");
    }

    public static Path requestsFile() {
        return dir().resolve("solicitudes-acceso.txt");
    }

    /**
     * Quiénes están en la sala del servidor de ESTA versión (lo escribe el servidor). Uno por versión del
     * protocolo (v10 = la 0.0.3: "conectados.txt"; v11 = la 0.0.4: "conectados-v11.txt"): si no, el servidor de
     * una versión borraría la lista del otro. El Laboratorio muestra la suma ({@link #online()}).
     */
    public static Path onlineFile() {
        return dir().resolve(Protocol.VERSION <= 10 ? "conectados.txt" : "conectados-v" + Protocol.VERSION + ".txt");
    }

    private static Path lockFile() {
        return dir().resolve("acceso.lock");
    }

    // ---------------------------------------------------------------- lista

    /** Lee la lista (la crea vacía si no existe). */
    public static AccessList load() {
        String host = "";
        List<Entry> entries = new ArrayList<>();
        try {
            if (!Files.exists(file())) return new AccessList("", entries); // sin archivo = nadie con permiso
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.toLowerCase(Locale.ROOT).startsWith(HOST_KEY)) {
                    host = clean(t.substring(HOST_KEY.length()));
                    continue;
                }
                String[] f = line.split("\t", -1);
                String name = clean(f[0]);
                if (name.isEmpty() || entries.size() >= MAX || entries.stream().anyMatch(e -> same(e.name, name))) continue;
                entries.add(new Entry(name, f.length > 1 ? f[1].strip() : "", f.length > 2 ? text(f[2]) : ""));
            }
        } catch (IOException e) {
            System.out.println("[ACCESO] No se pudo leer " + file() + ": " + e.getMessage());
        }
        return new AccessList(host, entries);
    }

    public String host() { return host; }

    public List<Entry> entries() { return List.copyOf(entries); }

    public List<String> names() { return entries.stream().map(Entry::name).toList(); }

    /**
     * ¿Puede entrar? Desde esta PC, siempre. Desde otra, solo si el nombre está
     * en la lista Y atado a esa misma cuenta (escribir el nombre de otro no basta).
     */
    public boolean isAllowed(String name, String identity) {
        if (LOCAL.equals(identity)) return true;
        String n = clean(name);
        if (n.isEmpty() || identity == null || identity.isEmpty()) return false;
        return entries.stream().anyMatch(e -> same(e.name, n) && e.identity.equals(identity));
    }

    /**
     * El nombre con el que quedó aceptada esta cuenta (pedido del usuario: "que la
     * cuenta recuerde el nombre"). Con él entra aunque escriba otro o nada, p. ej.
     * desde el celular con la misma cuenta de Tailscale. Vacío si no está aceptada.
     */
    public Optional<String> nameFor(String identity) {
        if (identity == null || identity.isEmpty() || LOCAL.equals(identity)) return Optional.empty();
        return entries.stream().filter(e -> e.identity.equals(identity)).map(Entry::name).findFirst();
    }

    private Optional<Entry> byName(String name) {
        return entries.stream().filter(e -> same(e.name, name)).findFirst();
    }

    /** Retira a una persona (el servidor la saca de la sala en unos segundos). No es un baneo. */
    public static synchronized void remove(String name) {
        locked(() -> {
            AccessList current = load();
            List<Entry> entries = new ArrayList<>(current.entries);
            entries.removeIf(e -> same(e.name, name));
            return writeList(current.host, entries);
        });
    }

    /** Tu nombre de jugador: es el que ve quien te pide permiso. Desde esta PC entras siempre. */
    public static synchronized void setHost(String name) {
        locked(() -> {
            AccessList current = load();
            return writeList(clean(name), current.entries);
        });
    }

    // ---------------------------------------------------------------- solicitudes

    /** Solicitudes pendientes, las más viejas primero (sin las vencidas). */
    public static List<Request> requests() {
        List<Request> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            if (!Files.exists(requestsFile())) return out;
            for (String line : Files.readAllLines(requestsFile(), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 7) continue;
                long at;
                try {
                    at = Long.parseLong(f[5].strip());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (now - at > REQUEST_TTL_MS) continue;
                out.add(new Request(f[0].strip(), clean(f[1]), f[2].strip(), text(f[3]), f[4].strip(), at, text(f[6])));
            }
        } catch (IOException e) {
            System.out.println("[ACCESO] No se pudo leer " + requestsFile() + ": " + e.getMessage());
        }
        return out;
    }

    /** ¿Sigue pendiente la solicitud de esta cuenta con este nombre? (false = la rechazaron o la aceptaron). */
    public static boolean hasRequest(String name, String identity) {
        return requests().stream().anyMatch(r -> r.identity.equals(identity) && same(r.name, name));
    }

    /**
     * Lo llama el servidor cuando alguien sin permiso se presenta: guarda (o
     * actualiza) SU solicitud. Una por cuenta: pedir con otro nombre reemplaza la
     * anterior, así nadie llena la lista cambiando de nombre.
     */
    public static synchronized Asked ask(String name, String identity, String label, String ip) {
        String n = clean(name);
        Asked[] result = {Asked.FULL};
        locked(() -> {
            AccessList list = load();
            List<Request> pending = new ArrayList<>(requests());
            Optional<Request> previous = pending.stream().filter(r -> r.identity.equals(identity)).findFirst();
            if (previous.isEmpty() && pending.size() >= MAX_PENDING) return true; // FULL
            pending.removeIf(r -> r.identity.equals(identity));
            String id = previous.map(Request::id).orElse(java.util.UUID.randomUUID().toString().substring(0, 8));
            pending.add(new Request(id, n, identity, text(label), ip, System.currentTimeMillis(), noteFor(list, n, identity)));
            result[0] = previous.isPresent() ? Asked.UPDATED : Asked.NEW;
            return writeRequests(pending);
        });
        return result[0];
    }

    /** Advertencias para el anfitrión antes de aceptar. */
    private static String noteFor(AccessList list, String name, String identity) {
        List<String> notes = new ArrayList<>();
        Optional<Entry> same = list.byName(name);
        if (same.isPresent() && same.get().bound()) {
            notes.add("⚠ Ese nombre ya es de OTRA cuenta (" + same.get().label + "): aceptar se lo pasa a esta.");
        } else if (same.isPresent()) {
            notes.add("Ya estaba en tu lista: confirma que esta es su cuenta.");
        }
        if (!list.host.isEmpty() && same(list.host, name)) notes.add("⚠ Usa TU nombre de anfitrión, desde otro equipo.");
        if (identity.startsWith("ip:")) notes.add("⚠ Sin cuenta de Tailscale: solo se sabe su IP.");
        return String.join(" ", notes);
    }

    /** Aceptar: el nombre queda atado a la cuenta de la solicitud. Devuelve un error, o null si salió bien. */
    public static synchronized String approve(String requestId) {
        String[] error = {null};
        locked(() -> {
            List<Request> pending = new ArrayList<>(requests());
            Optional<Request> request = pending.stream().filter(r -> r.id.equals(requestId)).findFirst();
            if (request.isEmpty()) {
                error[0] = "Esa solicitud ya no está (¿venció o ya la respondiste?).";
                return true;
            }
            Request r = request.get();
            AccessList current = load();
            List<Entry> entries = new ArrayList<>(current.entries);
            entries.removeIf(e -> e.identity.equals(r.identity) && !same(e.name, r.name)); // una cuenta = un nombre
            int index = -1;
            for (int i = 0; i < entries.size(); i++) if (same(entries.get(i).name, r.name)) index = i;
            Entry entry = new Entry(r.name, r.identity, r.label);
            if (index >= 0) {
                entries.set(index, entry); // mismo nombre: queda atado a esta cuenta
            } else if (entries.size() >= MAX) {
                error[0] = "La lista está llena (máx. " + MAX + "). Retira a alguien primero.";
                return true;
            } else {
                entries.add(entry);
            }
            pending.removeIf(x -> x.identity.equals(r.identity));
            return writeList(current.host, entries) && writeRequests(pending);
        });
        return error[0];
    }

    /** Rechazar: se borra la solicitud (quien esperaba recibe el aviso). Puede volver a pedir. */
    public static synchronized void reject(String requestId) {
        locked(() -> {
            List<Request> pending = new ArrayList<>(requests());
            pending.removeIf(r -> r.id.equals(requestId));
            return writeRequests(pending);
        });
    }

    // ---------------------------------------------------------------- conectados

    /** El servidor anota quiénes están en la sala. */
    public static void writeOnline(Collection<String> online) {
        try {
            Files.write(onlineFile(), online, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** Quiénes están en la sala según los servidores de esta PC, de cualquier versión (vacío si no hay servidor). */
    public static Set<String> online() {
        Set<String> out = new LinkedHashSet<>();
        try (java.util.stream.Stream<Path> files = Files.list(dir())) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (!name.startsWith("conectados") || !name.endsWith(".txt")) continue;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) out.add(line.trim().toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    // ---------------------------------------------------------------- archivos

    private interface IoAction {
        boolean run() throws IOException;
    }

    /** Candado de archivo: el servidor .exe y el Laboratorio son procesos distintos. */
    private static synchronized boolean locked(IoAction action) {
        try {
            Files.createDirectories(lockFile().getParent());
            try (FileChannel channel = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.run();
            }
        } catch (IOException e) {
            System.out.println("[ACCESO] No se pudo guardar: " + e.getMessage());
            return false;
        }
    }

    private static boolean writeList(String host, List<Entry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Lista de acceso al servidor VS Online (máx. ").append(MAX).append(" personas).\n");
        sb.append("# Una por línea: nombre<TAB>cuenta<TAB>cómo se muestra. La cuenta la pone el servidor al aceptar\n");
        sb.append("# una solicitud (Laboratorio > ACCESO); un nombre solo, sin cuenta, tiene que pedir permiso otra vez.\n");
        sb.append(HOST_KEY).append(host == null ? "" : host).append('\n');
        for (Entry e : entries) sb.append(e.name).append('\t').append(e.identity).append('\t').append(e.label).append('\n');
        replace(file(), sb.toString());
        return true;
    }

    private static boolean writeRequests(List<Request> pending) throws IOException {
        StringBuilder sb = new StringBuilder("# Solicitudes de acceso pendientes (las responde el anfitrión en Laboratorio > ACCESO).\n");
        for (Request r : pending) {
            sb.append(r.id).append('\t').append(r.name).append('\t').append(r.identity).append('\t').append(r.label)
                    .append('\t').append(r.ip).append('\t').append(r.at).append('\t').append(r.note).append('\n');
        }
        replace(requestsFile(), sb.toString());
        return true;
    }

    /** Archivo nuevo completo y después se cambia por el viejo: nunca se lee a medio escribir. */
    private static void replace(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                if (attempt >= 5) throw e; // Windows: alguien lo tenía abierto justo ahora; se reintenta un momento
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Mismo limpiado que el servidor aplica al nombre del jugador (sin tabuladores ni saltos de línea). */
    private static String clean(String name) {
        return Protocol.cleanText(name, Protocol.MAX_NAME_LENGTH);
    }

    /** Cuenta y advertencias: más largas, mismo limpiado (nada de tabuladores ni saltos que rompan el archivo). */
    private static String text(String text) {
        return Protocol.cleanText(text, 240);
    }

    private static boolean same(String a, String b) {
        return a.strip().equalsIgnoreCase(b.strip());
    }
}
