package org.example.online.client;

import javafx.application.Application;

/**
 * Punto de entrada de la ventana de prueba. Existe como clase aparte (que
 * NO extiende Application) para que JavaFX arranque también desde el
 * classpath con la tarea de Gradle runClient, sin configurar módulos.
 */
public final class LobbyTestLauncher {

    private LobbyTestLauncher() {}

    public static void main(String[] args) {
        Application.launch(LobbyTestWindow.class, args);
    }
}
