package org.example;

import javafx.application.Application;

/**
 * Punto de entrada del paquete descargable (jpackage). Ahí JavaFX viaja
 * en el classpath, y Java se niega a arrancar directo una clase que extiende
 * Application en ese caso ("JavaFX runtime components are missing"); desde
 * una clase que NO la extiende, sí arranca.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(Main.class, args);
    }
}
