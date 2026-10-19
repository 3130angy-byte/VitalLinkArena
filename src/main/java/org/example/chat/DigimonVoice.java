package org.example.chat;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Lo que dice el Digimon en su globo: frases predefinidas del banco (PhraseBank / PhrasePicker) según el evento
 * (aparecer, ganar, perder, ir a la sala...) y su atributo, etapa y Activity Type, más avisos fijos del programa.
 */
public final class DigimonVoice {

    private final List<Consumer<String>> bubbleListeners = new ArrayList<>();
    private PhrasePicker picker;

    public void setPhrasePicker(PhrasePicker picker) { this.picker = picker; }

    public void addOnBubbleMessageListener(Consumer<String> listener) { bubbleListeners.add(listener); }

    /**
     * Reacción a un evento con una frase del banco (puede quedarse callado en eventos que se repiten mucho).
     * Devuelve true si dijo algo.
     */
    public boolean react(String event, Map<String, String> vars) {
        if (picker == null) return false;
        Optional<String> text = picker.pick(event, vars);
        text.ifPresent(this::announce);
        return text.isPresent();
    }

    /** Aviso fijo e instantáneo (botones del menú, avisos del programa). */
    public void announce(String text) {
        if (text == null || text.isBlank()) return;
        if (Platform.isFxApplicationThread()) notifyBubble(text);
        else Platform.runLater(() -> notifyBubble(text));
    }

    private void notifyBubble(String text) {
        for (Consumer<String> listener : bubbleListeners) listener.accept(text);
    }
}
