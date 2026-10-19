package org.example.ui;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import org.example.Edition;
import org.example.online.Protocol;

import java.util.function.BiConsumer;

/**
 * Pantalla de inicio: primero tu NOMBRE DE USUARIO (el que verán los demás
 * en el VS Online; sin cuenta con correo por ahora, decisión del usuario),
 * el SERVIDOR del VS Online (127.0.0.1 = esta PC, o la IP de Tailscale del
 * anfitrión) y después traer tu Digimon del Vital Bracelet con una VS DIM.
 * Vuelve a aparecer cada vez que se retira un Digimon.
 */
public class StartScreen {

    /**
     * @param savedName   nombre guardado de la vez anterior (puede ser vacío)
     * @param savedHost   servidor guardado (o el de la edición)
     * @param onLoadVsDim recibe nombre y servidor ya validados y sigue con la carga de la VS DIM
     */
    public static Scene build(String savedName, String savedHost, BiConsumer<String, String> onLoadVsDim,
                              Runnable onOpenLab) {
        Label title = new Label("V-PET");
        title.setStyle("-fx-font-size: 28px; -fx-text-fill: white; -fx-font-weight: bold;");
        Label version = new Label(Edition.LABEL);
        version.setStyle("-fx-font-size: 10px; -fx-text-fill: #7c8691;");

        Label nameLabel = new Label("Tu nombre de usuario");
        nameLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #aab3bc;");

        TextField nameField = new TextField(savedName == null ? "" : savedName);
        nameField.setPromptText("Cómo te verán en el VS Online");
        nameField.setMaxWidth(220);
        // Mismo tope que el servidor: nunca se escribe un nombre que luego se recortaría.
        nameField.textProperty().addListener((obs, old, text) -> {
            if (text.length() > Protocol.MAX_NAME_LENGTH) nameField.setText(old);
        });

        Label hostLabel = new Label("Servidor del VS Online");
        hostLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #aab3bc;");
        TextField hostField = new TextField(savedHost == null ? "" : savedHost);
        hostField.setPromptText("127.0.0.1 o 100.x.x.x (Tailscale)");
        hostField.setMaxWidth(220);

        Label error = new Label();
        error.setStyle("-fx-font-size: 11px; -fx-text-fill: #ff8a8a;");

        Label subtitle = new Label("Trae a tu Digimon del Vital Bracelet");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #aab3bc;");

        Button btnVsDim = new Button("CARGAR MI DIGIMON (VS DIM)");
        Runnable go = () -> {
            String name = Protocol.cleanText(nameField.getText(), Protocol.MAX_NAME_LENGTH);
            if (name.isEmpty()) {
                error.setText("Escribe tu nombre de usuario.");
                nameField.requestFocus();
                return;
            }
            // Solo letras, números, puntos, guiones y dos puntos (IP o nombre de equipo); nada más.
            String host = hostField.getText().trim();
            if (host.isEmpty()) host = "127.0.0.1";
            if (!host.matches("[A-Za-z0-9.:\\-]{1,64}")) {
                error.setText("El servidor debe ser una IP, como 127.0.0.1 o 100.x.x.x (Tailscale).");
                hostField.requestFocus();
                return;
            }
            error.setText("");
            onLoadVsDim.accept(name, host);
        };
        btnVsDim.setOnAction(e -> go.run());
        nameField.setOnAction(e -> go.run());
        hostField.setOnAction(e -> go.run());

        // El Laboratorio se abre aunque no haya ningún Digimon en el escritorio.
        Button btnLab = new Button("LABORATORIO");
        btnLab.setOnAction(e -> {
            // Si ya escribió su nombre, vale para ESTE programa aunque entre por el Laboratorio
            // (antes la sala usaba el nombre guardado por otro programa abierto en la misma PC).
            String name = Protocol.cleanText(nameField.getText(), Protocol.MAX_NAME_LENGTH);
            String host = hostField.getText().trim();
            if (!name.isEmpty()) {
                org.example.chat.GameSettings.saveStartScreen(name,
                        host.matches("[A-Za-z0-9.:\\-]{1,64}") ? host : null);
            }
            onOpenLab.run();
        });

        VBox box = new VBox(8, title, version, nameLabel, nameField, hostLabel, hostField, error, subtitle, btnVsDim, btnLab);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: #202030;");

        return new Scene(box, 400, 400);
    }
}
