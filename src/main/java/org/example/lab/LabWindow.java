package org.example.lab;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.example.battle.DigimonRank;
import org.example.battle.PowerTrophyBonus;
import org.example.dim.DimSpriteImageFactory;
import org.example.dim.NameSpriteReader;
import org.example.dim.VsDimData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * LABORATORIO: la versión de PC de la app Vital Bracelet Lab (interfaz
 * SIMPLE a propósito, decisión del usuario). Es una ventana aparte: los
 * Digimon del escritorio siguen paseando mientras está abierta. Se abre
 * desde la pantalla de inicio o desde el menú V-PET > LAB de cualquier
 * Digimon; siempre es la MISMA ventana (si ya está abierta, pasa al frente).
 *
 * Pestañas: ALMACÉN (cápsulas = copias de VS DIM), DIGIDEX (especies vistas)
 * e HISTORIAL (batallas guardadas en disco).
 */
public final class LabWindow {

    /** Main lo registra al arrancar: el equipo del escritorio (puestos 1 y 2). */
    private static DesktopTeam team;
    private static LabWindow instance;

    private final Label teamLabel = new Label();

    private final Stage stage = new Stage();
    private final ListView<LabStorage.Capsule> capsules = new ListView<>();
    private final VBox detail = new VBox(6);
    private final ListView<Digidex.Entry> dex = new ListView<>();
    private final Label dexTitle = new Label();
    private final TableView<BattleHistory.Row> history = new TableView<>();

    public static void setDesktopTeam(DesktopTeam desktopTeam) {
        team = desktopTeam;
    }

    /** Main: al cerrar el Laboratorio sin Digimon en el escritorio vuelve la pantalla de inicio. */
    private static Runnable onClosing;

    public static void setOnClosing(Runnable action) {
        onClosing = action;
    }

    /** Tras cambiar el escritorio (sacar, reemplazar, retirar, intercambiar), si la ventana está abierta. */
    public static void refreshIfOpen() {
        if (instance != null && instance.stage.isShowing()) instance.refreshAll();
    }

    /** "Puesto 1" / "Puesto 2" si la cápsula está en el escritorio, o "" si no. */
    private static String slotOf(LabStorage.Capsule c) {
        if (team == null) return "";
        List<DesktopTeam.Member> members = team.members();
        for (int i = 0; i < members.size(); i++) {
            if (c.id().equals(members.get(i).capsuleId())) return "Puesto " + (i + 1);
        }
        return "";
    }

    public static boolean isOpen() {
        return instance != null && instance.stage.isShowing();
    }

    /** Abre el Laboratorio, o lo trae al frente si ya estaba abierto. */
    public static void open() {
        if (instance == null) instance = new LabWindow();
        instance.refreshAll();
        instance.stage.show();
        instance.stage.toFront();
    }

    private LabWindow() {
        TabPane tabs = new TabPane(
                new Tab("ALMACÉN", storageTab()),
                new Tab("DIGIDEX", dexTab()),
                new Tab("HISTORIAL", historyTab()),
                new Tab("RIVALES", rivalsTab()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> refreshAll());
        stage.setTitle("LABORATORIO");
        stage.setScene(new Scene(tabs, 760, 500));
        // ANTES de ocultarse: si la pantalla de inicio vuelve, nunca hay un instante sin ventanas.
        stage.setOnHiding(e -> { if (onClosing != null) onClosing.run(); });
    }

    private void refreshAll() {
        List<DesktopTeam.Member> members = team == null ? List.of() : team.members();
        teamLabel.setText("EN EL ESCRITORIO —  Puesto 1 (principal): " + (members.size() > 0 ? members.get(0).name() : "vacío")
                + "    ·    Puesto 2 (secundario, 2 vs 2): " + (members.size() > 1 ? members.get(1).name() : "vacío"));
        LabStorage.Capsule selected = capsules.getSelectionModel().getSelectedItem();
        capsules.setItems(FXCollections.observableArrayList(LabStorage.list()));
        if (selected != null) {
            capsules.getItems().stream().filter(c -> c.id().equals(selected.id())).findFirst()
                    .ifPresent(c -> capsules.getSelectionModel().select(c));
        }
        showDetail(capsules.getSelectionModel().getSelectedItem());
        var entries = Digidex.entries();
        dex.setItems(FXCollections.observableArrayList(entries));
        dexTitle.setText(entries.size() + " especies vistas (tus Digimon, tus cápsulas y los rivales).");
        history.setItems(FXCollections.observableArrayList(BattleHistory.rows()));
        refreshRivals();
    }

    // ---------------------------------------------------------------- RIVALES

    private final Label rivalFolderLabel = new Label();
    private final ListView<String> rivalFiles = new ListView<>();

    /**
     * Las DIM cards normales que dan los rivales de la Batalla aleatoria y de la
     * ARENA local (las VS DIM solo traen a su propio Digimon). En las dos
     * ediciones: elegir una carpeta o AGREGAR archivos .bin (se copian a la
     * carpeta de rivales).
     */
    private VBox rivalsTab() {
        Label note = new Label("Los rivales de la Batalla aleatoria y de la ARENA local salen de DIM cards NORMALES (.bin). "
                + "Elige una carpeta que las tenga o agrega archivos sueltos.");
        note.setWrapText(true);
        rivalFolderLabel.setStyle("-fx-font-weight: bold;");
        rivalFolderLabel.setWrapText(true);
        Button choose = new Button("ELEGIR CARPETA");
        choose.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Carpeta con DIM cards normales (.bin) para los rivales");
            File folder = chooser.showDialog(stage);
            if (folder == null) return;
            org.example.chat.GameSettings.saveRivalFolder(folder.getAbsolutePath());
            refreshRivals();
        });
        Button add = new Button("AGREGAR DIM CARDS");
        add.setOnAction(e -> addRivalCards());
        Button open = new Button("ABRIR CARPETA");
        open.setOnAction(e -> openExplorer(rivalFolder(), false));
        VBox box = new VBox(8, note, rivalFolderLabel, new HBox(8, choose, add, open), rivalFiles);
        box.setPadding(new Insets(10));
        VBox.setVgrow(rivalFiles, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    /** La carpeta de rivales elegida; si no hay, la del Laboratorio (laboratorio\rivales). */
    private static Path rivalFolder() {
        return org.example.chat.GameSettings.rivalDimFolder()
                .orElse(org.example.chat.AppPaths.lab().resolve("rivales"));
    }

    private void addRivalCards() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("DIM cards normales (.bin) para los rivales");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("DIM card", "*.bin"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;
        Path folder = rivalFolder();
        int copied = 0, vsDims = 0;
        try {
            java.nio.file.Files.createDirectories(folder);
            for (File f : files) {
                if (isVsDim(f.toPath())) { // una VS DIM no sirve de rival: solo trae a su Digimon
                    vsDims++;
                    continue;
                }
                java.nio.file.Files.copy(f.toPath(), folder.resolve(f.getName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
            if (org.example.chat.GameSettings.rivalDimFolder().isEmpty()) {
                org.example.chat.GameSettings.saveRivalFolder(folder.toString());
            }
        } catch (IOException ex) {
            alert(Alert.AlertType.ERROR, "No se pudieron copiar: " + ex.getMessage());
        }
        refreshRivals();
        alert(Alert.AlertType.INFORMATION, copied + " DIM card(s) agregadas a " + folder
                + (vsDims > 0 ? "\n" + vsDims + " eran VS DIM (no sirven de rivales) y no se copiaron." : ""));
    }

    private static boolean isVsDim(Path file) {
        try {
            org.example.dim.VsDimReader.read(file);
            return true;
        } catch (Exception notVsDim) {
            return false;
        }
    }

    private void refreshRivals() {
        Path folder = rivalFolder();
        boolean chosen = org.example.chat.GameSettings.rivalDimFolder().isPresent();
        List<String> names = new java.util.ArrayList<>();
        if (java.nio.file.Files.isDirectory(folder)) {
            try (var s = java.nio.file.Files.walk(folder, 3)) {
                s.filter(p -> p.toString().toLowerCase().endsWith(".bin"))
                        .forEach(p -> names.add(folder.relativize(p).toString()));
            } catch (IOException ignored) {
            }
        }
        rivalFolderLabel.setText((chosen ? "Carpeta de rivales: " : "Sin carpeta elegida (se usará): ") + folder
                + "   ·   " + names.size() + " archivo(s) .bin");
        rivalFiles.setItems(FXCollections.observableArrayList(names));
    }

    // ---------------------------------------------------------------- ALMACÉN

    private BorderPane storageTab() {
        Label note = new Label("Las cápsulas son COPIAS: tu Vital Bracelet conserva a su Digimon. "
                + "Si devuelves una cápsula al VB, recibe la versión de la cápsula (puede ser más vieja).");
        note.setWrapText(true);
        teamLabel.setStyle("-fx-font-weight: bold;");
        teamLabel.setWrapText(true);
        Button swap = new Button("INTERCAMBIAR PUESTOS 1 ⇄ 2");
        swap.setOnAction(e -> {
            if (team == null || team.members().size() < 2) {
                alert(Alert.AlertType.INFORMATION, "Para intercambiar hacen falta 2 Digimon en el escritorio.");
                return;
            }
            String error = team.swapOrder();
            if (error != null) alert(Alert.AlertType.WARNING, error);
        });
        Label slotsHelp = new Label("El puesto 1 pelea las batallas tipo VB; el puesto 2 solo entra en el 2 vs 2.");
        VBox top = new VBox(6, note, teamLabel, new HBox(10, swap, slotsHelp));

        capsules.setPrefWidth(330);
        capsules.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(LabStorage.Capsule c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String slot = slotOf(c);
                Label text = new Label((slot.isEmpty() ? "" : "★ " + slot + " (en el escritorio) · ") + c.species()
                        + " · " + stageName(c.data().character().stage()) + " · " + c.savedAt().format(LabStorage.WHEN));
                setGraphic(new VBox(2, nameStrip(DimSpriteImageFactory.toNativeImage(c.data().sprites().get(0))), text));
                setText(null);
            }
        });
        capsules.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> showDetail(c));

        Button importButton = new Button("IMPORTAR VS DIM");
        importButton.setOnAction(e -> importCapsule());
        Button desktop = new Button("AL ESCRITORIO");
        desktop.setOnAction(e -> withSelected(c -> {
            if (team == null) return;
            if (!slotOf(c).isEmpty()) {
                alert(Alert.AlertType.INFORMATION, c.species() + " ya está en el escritorio (" + slotOf(c) + ").");
                return;
            }
            // Escritorio lleno: se pregunta a quién reemplazar (vuelve a su cápsula, sin archivo).
            DesktopTeam.askReplace(stage, team.members(), c.species()).ifPresent(index -> {
                String error = team.place(c, index);
                if (error != null) alert(Alert.AlertType.WARNING, error);
            });
        }));
        Button export = new Button("EXPORTAR PARA EL VB");
        export.setOnAction(e -> withSelected(c -> {
            try {
                Path file = LabStorage.exportForVb(c);
                openExplorer(file, true);
            } catch (IOException ex) {
                alert(Alert.AlertType.ERROR, "No se pudo exportar: " + ex.getMessage());
            }
        }));
        Button delete = new Button("ELIMINAR");
        delete.setOnAction(e -> withSelected(c -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "¿Mover la cápsula de " + c.species() + " a la papelera del Laboratorio?");
            confirm.initOwner(stage);
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            try {
                LabStorage.delete(c);
                refreshAll();
            } catch (IOException ex) {
                alert(Alert.AlertType.ERROR, "No se pudo eliminar: " + ex.getMessage());
            }
        }));
        Button folder = new Button("ABRIR CARPETA");
        folder.setOnAction(e -> openExplorer(LabStorage.folder(), false));

        HBox buttons = new HBox(8, importButton, desktop, export, delete, folder);
        buttons.setPadding(new Insets(8));
        detail.setPadding(new Insets(8));

        BorderPane pane = new BorderPane();
        pane.setTop(top);
        BorderPane.setMargin(top, new Insets(8));
        pane.setLeft(capsules);
        // Con "lo que sabe de ti" el detalle puede no caber: se desplaza.
        javafx.scene.control.ScrollPane detailScroll = new javafx.scene.control.ScrollPane(detail);
        detailScroll.setFitToWidth(true);
        detailScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        pane.setCenter(detailScroll);
        pane.setBottom(buttons);
        return pane;
    }

    private void showDetail(LabStorage.Capsule c) {
        detail.getChildren().clear();
        if (c == null) {
            detail.getChildren().add(new Label(capsules.getItems().isEmpty()
                    ? "Todavía no hay cápsulas. Usa IMPORTAR VS DIM para guardar una copia de tu Digimon."
                    : "Elige una cápsula."));
            return;
        }
        VsDimData d = c.data();
        VsDimData.StatsRow s = d.character();
        ImageView idle = new ImageView(DimSpriteImageFactory.toNativeImage(d.sprites().get(1)));
        idle.setSmooth(false);
        idle.setScaleX(2);
        idle.setScaleY(2);
        HBox idleBox = new HBox(idle);
        idleBox.setAlignment(Pos.CENTER);
        idleBox.setPadding(new Insets(30));

        int points = d.powerTrophies();
        PowerTrophyBonus bonus = PowerTrophyBonus.forTrophies(points, s.dp(), s.hp());
        String rank = DigimonRank.load().rankFor(points);
        TextArea notes = new TextArea(c.notes());
        notes.setPrefRowCount(3);
        notes.setWrapText(true);
        Button saveNotes = new Button("GUARDAR NOTAS");
        saveNotes.setOnAction(e -> {
            try {
                LabStorage.saveNotes(c, notes.getText());
                refreshAll();
            } catch (IOException ex) {
                alert(Alert.AlertType.ERROR, "No se pudieron guardar las notas: " + ex.getMessage());
            }
        });

        detail.getChildren().addAll(idleBox,
                new Label(c.species() + " — " + stageName(s.stage()) + ", " + attributeName(s.attribute())),
                new Label("DP " + s.dp() + " (+" + bonus.dpBonus + ")   HP " + s.hp() + " (+" + bonus.hpBonus
                        + ")   AP " + s.ap() + " (+" + bonus.apBonus + ")"),
                new Label("Vital Values " + d.vitalValues() + "   ·   Trofeos " + points + "   ·   "
                        + (DigimonRank.NO_RANK.equals(rank) ? "sin rango" : "rango " + rank)),
                new Label("Edad hoy: " + c.ageDaysNow() + " días   ·   Guardada: " + c.savedAt().format(LabStorage.WHEN)),
                new Label("Archivo de origen: " + c.origin()),
                new Label("Notas:"), notes, saveNotes);
    }

    private void importCapsule() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("VS DIM para guardar en el Laboratorio");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("VS DIM", "*.bin"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Nueva cápsula");
        dialog.setHeaderText("La VS DIM no guarda la edad ni la especie en texto.");
        TextField age = new TextField("0");
        // La especie se lee del sprite NAME (NameSpriteReader); se puede corregir.
        TextField species = new TextField(NameSpriteReader.speciesOf(file.toPath()).orElse(""));
        species.setPromptText("Ej.: MagnaKidmon");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Días que tenía en el VB:"), age);
        grid.addRow(1, new Label("Especie (opcional):"), species);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        int days;
        try {
            days = Math.max(0, Integer.parseInt(age.getText().trim()));
        } catch (NumberFormatException e) {
            alert(Alert.AlertType.WARNING, "Escribe la edad como un número de días.");
            return;
        }
        try {
            String name = species.getText().replaceAll("[^A-Za-z0-9 .\\-]", "").trim();
            LabStorage.Capsule c = LabStorage.importVsDim(file.toPath(), name, days);
            Digidex.seen(c.data().sprites().get(0), c.species());
            refreshAll();
            capsules.getItems().stream().filter(x -> x.id().equals(c.id())).findFirst()
                    .ifPresent(x -> capsules.getSelectionModel().select(x));
        } catch (IOException e) {
            alert(Alert.AlertType.ERROR, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- DIGIDEX

    private VBox dexTab() {
        dex.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Digidex.Entry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String species = e.species().isEmpty() ? "" : e.species() + " · ";
                setGraphic(new VBox(2, nameStrip(new Image(e.image().toUri().toString())), new Label(species + "visto " + e.timesSeen()
                        + (e.timesSeen() == 1 ? " vez" : " veces") + " · desde " + e.firstSeen())));
                setText(null);
            }
        });
        VBox box = new VBox(8, dexTitle, dex);
        box.setPadding(new Insets(8));
        return box;
    }

    // ---------------------------------------------------------------- HISTORIAL

    private VBox historyTab() {
        addColumn("Fecha", BattleHistory.Row::when);
        addColumn("Digimon", BattleHistory.Row::digimon);
        addColumn("Modo", BattleHistory.Row::mode);
        addColumn("Rival", BattleHistory.Row::rival);
        addColumn("Resultado", BattleHistory.Row::result);
        addColumn("Vital Values", BattleHistory.Row::vitalValues);
        history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        history.setPlaceholder(new Label("Todavía no hay batallas guardadas."));
        VBox box = new VBox(8, new Label("Todas las batallas (Aleatoria, Oficiales y ARENA), guardadas en disco."), history);
        box.setPadding(new Insets(8));
        return box;
    }

    // ---------------------------------------------------------------- auxiliares

    /** El sprite NAME es texto claro sobre fondo transparente: se muestra sobre una franja oscura. */
    private static HBox nameStrip(Image nameImage) {
        ImageView view = new ImageView(nameImage);
        view.setSmooth(false);
        HBox strip = new HBox(view);
        strip.setPadding(new Insets(3, 6, 3, 6));
        strip.setMaxWidth(Region.USE_PREF_SIZE);
        strip.setStyle("-fx-background-color: #202030; -fx-background-radius: 4;");
        return strip;
    }

    private void addColumn(String title, Function<BattleHistory.Row, String> value) {
        TableColumn<BattleHistory.Row, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(value.apply(cd.getValue())));
        history.getColumns().add(c);
    }

    private void withSelected(java.util.function.Consumer<LabStorage.Capsule> action) {
        LabStorage.Capsule c = capsules.getSelectionModel().getSelectedItem();
        if (c == null) {
            alert(Alert.AlertType.INFORMATION, "Primero elige una cápsula de la lista.");
            return;
        }
        action.accept(c);
    }

    private void alert(Alert.AlertType type, String text) {
        Alert a = new Alert(type, text);
        a.initOwner(stage);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private static void openExplorer(Path path, boolean select) {
        try {
            if (select) new ProcessBuilder("explorer.exe", "/select,", path.toAbsolutePath().toString()).start();
            else new ProcessBuilder("explorer.exe", path.toAbsolutePath().toString()).start();
        } catch (IOException e) {
            System.out.println("[LAB] Abre a mano: " + path);
        }
    }

    public static String stageName(int stage) {
        return switch (stage) {
            case 0 -> "Baby I";
            case 1 -> "Baby II";
            case 2 -> "Child";
            case 3 -> "Adult";
            case 4 -> "Perfect";
            default -> "Ultimate";
        };
    }

    private static String attributeName(int attribute) {
        return switch (attribute) {
            case 1 -> "Virus";
            case 2 -> "Data";
            case 3 -> "Vaccine";
            case 4 -> "Free";
            default -> "Null";
        };
    }
}
