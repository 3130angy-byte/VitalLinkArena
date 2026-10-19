package org.example.online.server;

import org.example.online.Protocol;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * QUIÉN se conecta, según Tailscale (capa 1 de acceso, 2026-10-02, pedido del
 * usuario): el nombre que cada uno escribe al iniciar se puede inventar; la
 * cuenta de Tailscale desde la que llega la conexión, no. El servidor le
 * pregunta a Tailscale de esta PC con {@code tailscale whois --json <ip>} y la
 * lista de acceso ata cada nombre a esa cuenta.
 *
 * Tres casos:
 *  - conexión desde ESTA PC (127.0.0.1 o una IP propia, también la de
 *    Tailscale): "local", es el anfitrión;
 *  - IP de Tailscale (100.64.0.0/10 o fd7a:115c:a1e0::/48): la cuenta
 *    ("ts:" + id de usuario), con correo y equipo para mostrar;
 *  - cualquier otra (red de la casa) o si Tailscale no responde: solo la IP
 *    ("ip:" + dirección), más débil; se marca así en las solicitudes.
 *
 * Ojo: con "tailscale serve" reenviando el puerto, los de afuera llegarían como
 * 127.0.0.1 (= "local"). El servidor del juego NO debe publicarse así.
 */
final class TailscaleIdentity {

    static final String LOCAL = "local";

    /** key = lo que se guarda y compara; label = lo que ve el anfitrión. */
    record Identity(String key, String label) {
        boolean weak() { return key.startsWith("ip:"); }
    }

    private static final long CACHE_MS = 60_000;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(Identity identity, long at) {}

    private TailscaleIdentity() {}

    /** Quién responde la pregunta; las pruebas (mismo paquete) lo cambian para simular otros equipos. */
    static volatile java.util.function.Function<InetAddress, Identity> resolver = TailscaleIdentity::resolve;

    static Identity of(InetAddress address) {
        return resolver.apply(address);
    }

    private static Identity resolve(InetAddress address) {
        String ip = address.getHostAddress();
        if (isThisPc(address)) return new Identity(LOCAL, "esta PC");
        Cached cached = CACHE.get(ip);
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_MS) return cached.identity;
        Identity identity = isTailscale(address) ? whois(ip) : null;
        if (identity == null) identity = new Identity("ip:" + ip, "IP " + ip + " (sin cuenta de Tailscale)");
        CACHE.put(ip, new Cached(identity, System.currentTimeMillis()));
        return identity;
    }

    private static boolean isThisPc(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException e) {
            return false;
        }
    }

    /**
     * La IP de Tailscale de ESTA PC (100.x.x.x), la que el anfitrión les pasa a sus amigos; null si Tailscale no
     * está instalado o no está conectado. Solo mira las interfaces de red (no ejecuta nada).
     */
    static String ownIpv4() {
        try {
            for (NetworkInterface nic : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && isTailscale(a)) return a.getHostAddress();
                }
            }
        } catch (SocketException | RuntimeException ignored) {
        }
        return null;
    }

    private static boolean isTailscale(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10
        }
        return address instanceof Inet6Address && (b[0] & 0xFF) == 0xFD && (b[1] & 0xFF) == 0x7A
                && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0x5C && (b[4] & 0xFF) == 0xA1 && (b[5] & 0xFF) == 0xE0;
    }

    /** null si Tailscale no está o no sabe de esa IP. La IP va como argumento aparte (nunca por una consola). */
    private static Identity whois(String ip) {
        Path output = null;
        try {
            // A un archivo y no a una tubería: si Tailscale se colgara, el servidor no se queda esperando.
            output = Files.createTempFile("vs-whois", ".json");
            Process p = new ProcessBuilder(executable(), "whois", "--json", ip)
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 || Files.size(output) > 256 * 1024) return null;
            JSONObject json = new JSONObject(Files.readString(output, StandardCharsets.UTF_8));
            JSONObject node = json.optJSONObject("Node");
            JSONObject user = json.optJSONObject("UserProfile");
            String machine = node == null ? "" : node.optString("ComputedName", node.optString("Name", ""));
            String login = user == null ? "" : user.optString("LoginName", "");
            long userId = user == null ? 0 : user.optLong("ID", 0);
            if (userId != 0 && !login.isBlank()) {
                return new Identity("ts:" + userId, clean(login + (machine.isBlank() ? "" : " · " + machine)));
            }
            // Sin perfil de usuario (p. ej. un equipo con etiqueta): se ata al EQUIPO.
            String stable = node == null ? "" : node.optString("StableID", "");
            if (!stable.isBlank()) return new Identity("ts-equipo:" + stable, clean("equipo " + machine));
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String executable() {
        Path windows = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Tailscale", "tailscale.exe");
        return Files.isRegularFile(windows) ? windows.toString() : "tailscale";
    }

    private static String clean(String text) {
        return Protocol.cleanText(text, 80);
    }
}
