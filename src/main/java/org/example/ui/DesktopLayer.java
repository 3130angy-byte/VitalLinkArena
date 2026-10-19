package org.example.ui;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Dueño invisible de las ventanas que viven SOBRE el escritorio: el Digimon,
 * su portal y su globo (pedido del usuario, 2026-10-02: que los Digimon no
 * ocupen la barra de tareas y sigan a la vista con "Mostrar escritorio").
 * Sala online, Laboratorio y peleas siguen siendo ventanas normales.
 *
 * En Windows una ventana con dueño no tiene botón en la barra de tareas, y si
 * el dueño es una ventana de herramientas (UTILITY), "Mostrar escritorio" no
 * la minimiza. Probado en esta PC con Shell.Application.ToggleDesktop(): la
 * ventana sin dueño quedó minimizada; la con dueño siguió visible y sin botón.
 *
 * Como el dueño queda abierto siempre, JavaFX ya no cerraría el programa solo
 * al cerrar la última ventana: lo hace esta clase, con la misma regla.
 */
public final class DesktopLayer {

    private static Stage owner;

    private DesktopLayer() {}

    /** Llamar ANTES de mostrar la ventana (JavaFX solo acepta el dueño antes de show). */
    public static void attach(Stage stage) {
        stage.initOwner(owner());
    }

    private static Stage owner() {
        if (owner == null) {
            owner = new Stage(StageStyle.UTILITY);
            owner.setTitle("V-PET");
            owner.setOpacity(0);
            owner.setWidth(1);
            owner.setHeight(1);
            owner.setX(-32000);
            owner.setY(-32000);
            owner.setScene(new Scene(new Pane(), 1, 1));
            owner.show();
            Platform.setImplicitExit(false);
            Window.getWindows().addListener((ListChangeListener<Window>) change ->
                    Platform.runLater(DesktopLayer::exitIfNothingOpen));
        }
        return owner;
    }

    /** Sin ninguna ventana a la vista (fuera del dueño) el programa termina, como antes. */
    private static void exitIfNothingOpen() {
        boolean anyOpen = Window.getWindows().stream().anyMatch(w -> w != owner && w.isShowing());
        if (!anyOpen) Platform.exit();
    }
}
