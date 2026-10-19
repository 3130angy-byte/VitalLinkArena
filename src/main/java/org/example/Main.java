package org.example;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import org.example.behavior.DigimonInstance;
import org.example.behavior.DigimonRegistry;
import org.example.chat.AppPaths;
import org.example.chat.GameSettings;
import org.example.dim.NameSpriteReader;
import org.example.dim.VsDimData;
import org.example.dim.VsDimReader;
import org.example.lab.DesktopTeam;
import org.example.lab.Digidex;
import org.example.lab.LabStorage;
import org.example.lab.LabWindow;
import org.example.online.client.LobbyWindow;
import org.example.ui.StartScreen;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * VitalLink Arena: tu Digimon del Vital Bracelet en el escritorio. Sin crianza: la única forma de tener un
 * Digimon es traerlo del Vital Bracelet con una VS DIM, y la única forma de devolverlo es RETIRARLO (menú V-PET),
 * que genera la VS DIM de vuelta con el saldo de sus batallas.
 */
public class Main extends Application {

    private static final int MAX_DIGIMON = 2;

    private Stage navigationStage;
    private final DigimonRegistry registry = new DigimonRegistry();

    /** Lo que se pregunta al importar: la VS DIM no guarda la edad (ni la especie en texto). */
    private record ImportAnswers(int ageDays, String species) {}

    @Override
    public void start(Stage stage) {
        AppPaths.base(); // deja lista la carpeta de datos desde el arranque
        org.example.sound.Sounds.preload(); // efectos de sonido de DigimonProjectData\sounds (si están)

        this.navigationStage = stage;
        stage.setTitle(Edition.LABEL);
        LabWindow.setDesktopTeam(desktopTeam);
        LabWindow.setOnClosing(this::showStartScreenIfDesktopEmpty);
        showStartScreen();
    }

    /**
     * El escritorio visto desde el Laboratorio: puesto 1 y 2 = orden del
     * registro. Reemplazar NO retira (no genera archivo): el reemplazado
     * vuelve a su cápsula por el portal y el nuevo ocupa su puesto.
     */
    private final DesktopTeam desktopTeam = new DesktopTeam() {
        @Override
        public List<Member> members() {
            return registry.getActiveInstances().stream()
                    .map(d -> new Member(d.getCapsuleId(), d.getDisplayName())).toList();
        }

        @Override
        public String place(LabStorage.Capsule capsule, int replaceIndex) {
            if (registry.getActiveInstances().stream().anyMatch(d -> capsule.id().equals(d.getCapsuleId()))) {
                return capsule.species() + " ya está en el escritorio.";
            }
            String blocker = LobbyWindow.teamChangeBlocker();
            if (blocker != null) return blocker;
            if (replaceIndex < 0) return spawnCapsule(capsule, registry.getActiveInstances().size(), false);
            if (replaceIndex >= registry.getActiveInstances().size()) return "Ese puesto está vacío.";
            DigimonInstance old = registry.getActiveInstances().get(replaceIndex);
            // Si el reemplazado está en la sala online, el cambio pasa ALLÁ (pedido del usuario):
            // se va por un portal que queda abierto y por él sale el nuevo; el escritorio no cambia.
            boolean inLobby = old.isInLobby();
            if (inLobby) LobbyWindow.beginPortalSwap();
            old.dismiss(() -> {
                registry.remove(old.getInstanceId());
                String error = spawnCapsule(capsule, replaceIndex, inLobby);
                if (error != null) showError(error);
                LabWindow.refreshIfOpen();
                // spawnCapsule ya reenvía el equipo si salió bien (dos envíos seguidos los frena el servidor).
                if (error != null) LobbyWindow.notifyTeamChanged();
            });
            return null;
        }

        @Override
        public String swapOrder() {
            String blocker = LobbyWindow.teamChangeBlocker();
            if (blocker != null) return blocker;
            Optional<DigimonInstance> first = registry.primary(), second = registry.secondary();
            if (first.isPresent() && second.isPresent() && first.get().isInLobby()) {
                // Puesto 1 en la sala y puesto 2 en el escritorio (pedido del usuario): cada uno
                // entra a su portal y sale por el del otro. El portal de la sala queda abierto
                // hasta que el del escritorio termina de entrar.
                DigimonInstance toDesktop = first.get(), toLobby = second.get();
                if (toLobby.isAway()) return toLobby.getDisplayName() + " está fuera (en una pelea); espera a que vuelva.";
                registry.swapOrder();
                LabWindow.refreshIfOpen();
                LobbyWindow.beginPortalSwap();
                // El portal del escritorio queda abierto: por él sale el que estaba en la sala.
                toLobby.goToLobby(portal -> {
                    if (LobbyWindow.isAnyOpen() && toDesktop.isInLobby()) {
                        toDesktop.leaveLobbyThrough(portal);
                        LobbyWindow.notifyTeamChanged();
                    } else {
                        toLobby.leaveLobbyThrough(portal); // cerraste la sala mientras entraba: vuelve a salir
                    }
                });
                return null;
            }
            registry.swapOrder();
            LabWindow.refreshIfOpen();
            LobbyWindow.notifyTeamChanged();
            return null;
        }
    };

    /**
     * Nombre y servidor guardados de la vez anterior: se piden antes de cargar la VS DIM.
     * La pantalla de inicio solo está cuando hace falta (pedido del usuario, 2026-10-02):
     * se va al cargar un Digimon o al abrir el Laboratorio, y vuelve cuando el escritorio
     * queda vacío (retirar al último, o cerrar el Laboratorio sin haber sacado a nadie).
     */
    private void showStartScreen() {
        navigationStage.setScene(StartScreen.build(GameSettings.savedPlayerName(), GameSettings.onlineHost(),
                this::onLoadVsDim, this::openLabFromStart));
        navigationStage.show();
        navigationStage.toFront();
    }

    private void showStartScreenIfDesktopEmpty() {
        if (registry.getActiveInstances().isEmpty()) showStartScreen();
    }

    /** Primero se abre el Laboratorio y después se oculta el inicio: nunca hay un instante sin ventanas. */
    private void openLabFromStart() {
        LabWindow.open();
        navigationStage.hide();
    }

    // ---------- VS DIM: Digimon traído del Vital Bracelet ----------

    /**
     * Cargar desde la pantalla de inicio = guardar la VS DIM como cápsula del
     * Laboratorio (o reusar la misma si ya estaba) y sacarla al escritorio.
     * Con el escritorio lleno se pregunta a quién reemplazar.
     */
    private void onLoadVsDim(String playerName, String host) {
        GameSettings.saveStartScreen(playerName, host);

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Selecciona una VS DIM (.bin)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("VS DIM", "*.bin"));
        File file = chooser.showOpenDialog(navigationStage);
        if (file == null) return;

        // Se valida ANTES de pedir la edad: si no es una VS DIM con Digimon, se avisa y ya.
        try {
            VsDimData vs = VsDimReader.read(file.toPath());
            System.out.println("VS DIM: slot=" + vs.slot() + " vitals=" + vs.vitalValues()
                    + " powerTrophies=" + vs.powerTrophies());
        } catch (IOException ex) {
            showError(ex.getMessage());
            return;
        }

        LabStorage.Capsule capsule;
        try {
            Optional<LabStorage.Capsule> same = LabStorage.findSame(file.toPath());
            if (same.isPresent()) {
                capsule = same.get(); // misma VS DIM ya guardada: se reusa (con su edad, especie y récord)
            } else {
                // La especie se lee del sprite NAME (NameSpriteReader); el usuario puede corregirla.
                String read = NameSpriteReader.speciesOf(file.toPath()).orElse("");
                Optional<ImportAnswers> answers = askImportAnswers(read);
                if (answers.isEmpty()) return;
                String species = answers.get().species().isBlank() ? read : answers.get().species();
                capsule = LabStorage.importVsDim(file.toPath(), species, answers.get().ageDays());
            }
        } catch (IOException ex) {
            showError(ex.getMessage());
            return;
        }

        Optional<Integer> slot = DesktopTeam.askReplace(navigationStage, desktopTeam.members(), capsule.species());
        if (slot.isEmpty()) return;
        String error = desktopTeam.place(capsule, slot.get());
        if (error != null) {
            showError(error);
            return;
        }
        navigationStage.hide();
    }

    /**
     * Pone el Digimon de una cápsula en el escritorio, en el puesto {@code index}
     * (0 = puesto 1). Llega por el portal del centro de la pantalla. Edad = la
     * guardada + los días transcurridos. Devuelve un error, o null si salió bien.
     */
    private String spawnCapsule(LabStorage.Capsule capsule, int index, boolean intoLobby) {
        if (registry.getActiveInstances().size() >= MAX_DIGIMON) {
            return "Ya hay " + MAX_DIGIMON + " Digimon en el escritorio.";
        }
        Digidex.seen(capsule.data().sprites().get(0), capsule.species());
        // Esta posición solo le da la dirección de salida del portal.
        double offset = index * 140;
        double x = Screen.getPrimary().getVisualBounds().getMaxX() - 150 - offset;
        double y = Screen.getPrimary().getVisualBounds().getMaxY() - 56;
        DigimonInstance instance = DigimonInstance.fromVsDim(capsule.file(), capsule.ageDaysNow());
        instance.setCapsuleId(capsule.id());
        instance.setSpawnIntoLobby(intoLobby); // reemplazo del puesto 1 con la sala abierta: aparece allá
        instance.setTypedSpecies(capsule.species());
        instance.setOnRetired(retired -> {
            registry.remove(retired.getInstanceId());
            LabWindow.refreshIfOpen();
            // Tras retirar vuelve la pantalla de inicio, salvo que quede otro Digimon o el Laboratorio abierto.
            if (!LabWindow.isOpen()) showStartScreenIfDesktopEmpty();
        });
        registry.putAt(index, instance);
        try {
            instance.spawn(x, y, registry);
            LabWindow.refreshIfOpen();
            LobbyWindow.notifyTeamChanged();
            return null;
        } catch (Exception ex) {
            registry.remove(instance.getInstanceId());
            return "No se pudo iniciar el Digimon de la VS DIM: " + ex.getMessage();
        }
    }

    /**
     * La VS DIM no guarda la edad ni la especie en texto: se le preguntan al
     * usuario (la especie viene prellenada con la que se lee del sprite NAME;
     * vacía = "Digimon"). Vacío el Optional = canceló.
     */
    private Optional<ImportAnswers> askImportAnswers(String readSpecies) {
        while (true) {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(navigationStage);
            dialog.setTitle("Tu Digimon");
            dialog.setHeaderText("La VS DIM no guarda la edad del Digimon.");
            TextField ageField = new TextField("0");
            TextField speciesField = new TextField(readSpecies);
            speciesField.setPromptText("Ej.: MagnaKidmon");
            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(8);
            grid.setPadding(new Insets(10));
            grid.addRow(0, new Label("¿Cuántos días tenía en el Vital Bracelet?"), ageField);
            grid.addRow(1, new Label("¿Qué Digimon es? (especie)"), speciesField);
            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return Optional.empty();
            try {
                int days = Integer.parseInt(ageField.getText().trim());
                String species = speciesField.getText().replaceAll("[^A-Za-z0-9 .\\-]", "").trim();
                if (species.length() > 24) species = species.substring(0, 24);
                if (days >= 0) return Optional.of(new ImportAnswers(days, species));
            } catch (NumberFormatException ignored) {
                // se vuelve a preguntar
            }
            showError("Escribe un número de días (0 o más).");
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        if (navigationStage.isShowing()) alert.initOwner(navigationStage); // oculta mientras hay Digimon o Laboratorio
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
