package org.example.online.client;

import org.example.online.Protocol;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Conexión del cliente al servidor VS Online. No sabe nada de JavaFX: lee
 * en su propio hilo y entrega cada mensaje a un listener (quien lo use en
 * la interfaz debe pasarlo al hilo de JavaFX con Platform.runLater).
 */
public class VsClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final Socket socket = new Socket();
    private Writer out;
    private volatile boolean closedByUs = false;

    public void connect(String host, int port, String name,
                        Consumer<JSONObject> onMessage, Consumer<String> onDisconnected) throws IOException {
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        socket.setTcpNoDelay(true);
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        Thread reader = new Thread(() -> {
            String reason = "Conexión cerrada por el servidor.";
            try (Reader in = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = Protocol.readLine(in)) != null) {
                    try {
                        onMessage.accept(new JSONObject(line));
                    } catch (JSONException e) {
                        System.out.println("VsClient: mensaje inválido del servidor, ignorado.");
                    }
                }
            } catch (IOException e) {
                reason = "Se perdió la conexión: " + e.getMessage();
            }
            if (!closedByUs) onDisconnected.accept(reason);
        }, "vs-client-reader");
        reader.setDaemon(true);
        reader.start();

        send(Protocol.msg("hello").put("v", Protocol.VERSION).put("name", name));

        // "Sigo aquí": sin esto, el servidor corta a quien pase quieto más de 90 s.
        Thread pinger = new Thread(() -> {
            while (!closedByUs && !socket.isClosed()) {
                try {
                    Thread.sleep(Protocol.PING_INTERVAL_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                send(Protocol.msg("ping"));
            }
        }, "vs-client-ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    /** Tu Digimon (mensaje armado con LobbyDigimon.payloadFrom). El servidor lo valida y calcula el rango. */
    public void sendDigimon(JSONObject payload) {
        send(payload);
    }

    // ---- Batallas Oficiales (el servidor valida y decide todo) ----

    public void setAvailable(boolean on) {
        send(Protocol.msg("available").put("on", on));
    }

    public void listAvailable() {
        send(Protocol.msg("listAvailable"));
    }

    /** mode = Protocol.MODE_FREE (con bono de puntos) o Protocol.MODE_ORIGINAL (stats de la DIM). */
    public void challenge(int targetId, String mode) {
        send(Protocol.msg("challenge").put("target", targetId).put("mode", mode));
    }

    public void replyChallenge(int fromId, boolean accept) {
        send(Protocol.msg("challengeReply").put("from", fromId).put("accept", accept));
    }

    public void battleDone() {
        send(Protocol.msg("battleDone"));
    }

    // ---- ARENA 2 vs 2 online (el servidor decide turnos y daño) ----

    /** "attack", "switch" o "w". */
    public void arenaAction(String action) {
        send(Protocol.msg("arenaAction").put("action", action));
    }

    public void arenaCombo(int combo) {
        send(Protocol.msg("arenaCombo").put("combo", combo));
    }

    /** Defensa elegida: protect = recibe el golpe el compañero (PROTECT); si no, la distancia del minijuego. */
    public void arenaDefense(double distance, boolean protect) {
        send(Protocol.msg("arenaDefense").put("distance", distance).put("protect", protect));
    }

    // ---- Torneos (v12; el servidor decide quién puede hacer cada cosa) ----

    /** Crear un torneo: mode = libre / original / arena; size = 4, 8 o 16; play = el organizador también juega. */
    public void tournamentCreate(String mode, int size, boolean play) {
        send(Protocol.msg("tCreate").put("mode", mode).put("size", size).put("play", play));
    }

    public void tournamentJoin(int id) {
        send(Protocol.msg("tJoin").put("id", id));
    }

    /** El organizador acepta o rechaza a quien pidió entrar. */
    public void tournamentAnswer(int id, String name, boolean accept) {
        send(Protocol.msg("tAnswer").put("id", id).put("name", name).put("accept", accept));
    }

    public void tournamentStart(int id) {
        send(Protocol.msg("tStart").put("id", id));
    }

    public void tournamentCancel(int id) {
        send(Protocol.msg("tCancel").put("id", id));
    }

    /** Retirarse (antes de empezar, sin castigo; ya empezado, descalificado de ese torneo). */
    public void tournamentLeave(int id) {
        send(Protocol.msg("tLeave").put("id", id));
    }

    /** "LISTO" para el combate que preguntó el servidor. */
    public void tournamentReady() {
        send(Protocol.msg("tReady"));
    }

    public void moveTo(double x, double y) {
        send(Protocol.msg("move").put("x", Math.round(x)).put("y", Math.round(y)));
    }

    public void chat(String text) {
        send(Protocol.msg("chat").put("text", text));
    }

    public void close() {
        closedByUs = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private synchronized void send(JSONObject message) {
        if (out == null) return;
        try {
            out.write(Protocol.line(message));
            out.flush();
        } catch (IOException e) {
            close();
        }
    }
}
