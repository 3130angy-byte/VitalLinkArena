package org.example.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;

import org.example.Edition;
import org.example.lab.LabWindow;
import org.example.animation.VPetStageTier;
import org.example.behavior.CompanionController;
import org.example.behavior.VPetMovementMode;
import org.example.sound.Sfx;
import org.example.sound.Sounds;

/**
 * Menú del Digimon (diseño del usuario): página V-PET con Batalla, Movimiento, Digimon, Retirar, LAB y Sonido.
 * Cada categoría abre una subpágina con sus opciones y "← VOLVER". Batalla abre su subpágina: Aleatoria,
 * ARENA 2 vs 2 y VS Online.
 *
 * Se muestra al costado del Digimon con una flechita que lo señala; si no
 * cabe a la derecha, se abre a la izquierda.
 */
public class VPetMenu {

    private enum Page { VPET, BATALLA, MOVIMIENTO }

    // Estilo del diseño: panel oscuro, título turquesa, texto blanco en mayúsculas.
    private static final String PANEL_BG = "#26292e";
    private static final String PANEL_BORDER = "#3b4249";
    private static final Color TITLE_COLOR = Color.web("#6fd3e0");
    private static final String HOVER_BG = "#343a41";
    private static final double PANEL_WIDTH = 270;
    private static final double ARROW_SIZE = 10;
    private static final Font TITLE_FONT = Font.font("Consolas", FontWeight.BOLD, 16);
    private static final Font ITEM_FONT = Font.font("Consolas", FontWeight.BOLD, 13);
    private static final Font SMALL_FONT = Font.font("Consolas", 11);

    private final Window ownerWindow;
    private final CompanionController companion;
    private VPetStageTier stageTier;
    private final Popup popup = new Popup();
    private final StackPane root = new StackPane();
    private final Runnable onBattleRandom;
    private final Runnable onVsOnline;
    private final Runnable onOpenInfoPanel;
    private final Runnable onRetire;
    private final Runnable onArena;
    private Page currentPage = Page.VPET;
    private boolean arrowOnLeft = true;

    public VPetMenu(Window ownerWindow, CompanionController companion, VPetStageTier stageTier,
                    Runnable onBattleRandom, Runnable onVsOnline, Runnable onOpenInfoPanel, Runnable onRetire,
                    Runnable onArena) {
        this.onVsOnline = onVsOnline;
        this.onRetire = onRetire;
        this.onArena = onArena;
        this.ownerWindow = ownerWindow;
        this.companion = companion;
        this.stageTier = stageTier;
        this.onBattleRandom = onBattleRandom;
        this.onOpenInfoPanel = onOpenInfoPanel;
        popup.getContent().setAll(root);
        // Un Popup no trae la clase ".root" del tema: sin ella los botones no encuentran sus
        // colores base y JavaFX avisa "Could not resolve '-fx-text-base-color'" (inofensivo).
        root.getStyleClass().add("root");
        popup.setAutoHide(true);
        showPage(Page.VPET);
    }

    public void setStageTier(VPetStageTier stageTier) {
        this.stageTier = stageTier;
        showPage(currentPage);
    }

    /** Abre/cierra el menú al costado del Digimon. Siempre abre en la página V-PET. */
    public void toggle(Window pet) {
        if (popup.isShowing()) {
            Sounds.play(Sfx.CANCELAR);
            popup.hide();
            return;
        }
        Sounds.play(Sfx.SELECCIONAR);
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double rightX = pet.getX() + pet.getWidth() + 4;
        arrowOnLeft = rightX + PANEL_WIDTH + ARROW_SIZE <= screen.getMaxX();
        showPage(Page.VPET);
        root.applyCss();
        root.layout();
        double height = root.prefHeight(-1);
        double x = arrowOnLeft ? rightX : pet.getX() - PANEL_WIDTH - ARROW_SIZE - 4;
        double y = pet.getY() + pet.getHeight() / 2.0 - height / 2.0;
        y = Math.max(screen.getMinY(), Math.min(y, screen.getMaxY() - height));
        popup.show(ownerWindow, x, y);
    }

    // ---------------------------------------------------------------------
    // Páginas

    private void showPage(Page page) {
        currentPage = page;
        VBox panel = switch (page) {
            case VPET -> vpetPage();
            case BATALLA -> batallaPage();
            case MOVIMIENTO -> movimientoPage();
        };
        root.getChildren().setAll(withArrow(panel));
        if (popup.isShowing()) popup.sizeToScene();
    }

    private VBox vpetPage() {
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.add(tile("⚔", "#d8dde3", "BATALLA", true, () -> showPage(Page.BATALLA)), 0, 0);
        grid.add(tile("➜", "#8fd6ff", "MOVIMIENTO", true, () -> showPage(Page.MOVIMIENTO)), 1, 0);
        grid.add(tile("☻", "#b48cff", "DIGIMON", true, () -> { popup.hide(); onOpenInfoPanel.run(); }), 0, 1);
        // Devuelve al Digimon al Vital Bracelet con el saldo de sus batallas (pide confirmación).
        grid.add(tile("⇪", "#ffb35c", "RETIRAR", true, () -> { popup.hide(); onRetire.run(); }), 1, 1);
        // Laboratorio: ventana aparte; el Digimon sigue en el escritorio mientras está abierta.
        grid.add(tile("⚗", "#7fe0a8", "LAB", true, () -> { popup.hide(); LabWindow.open(); }), 0, 2);
        grid.add(soundTile(), 1, 2);
        if (prizeOwned.getAsBoolean()) grid.add(prizeTile(), 0, 3); // premio de campeón de torneo

        Label version = new Label("versión " + Edition.VERSION);
        version.setTextFill(Color.web("#7c8691"));
        version.setFont(SMALL_FONT);
        return panel("V-PET", grid, rightAligned(version));
    }

    /**
     * Premio del campeón de un torneo del VS Online (pedido del usuario, 2026-10-18): lucir al Digimon al DOBLE en
     * pixel art. Se prende y se apaga aquí; la casilla solo aparece si este Digimon ganó un torneo.
     */
    private java.util.function.BooleanSupplier prizeOwned = () -> false;
    private java.util.function.BooleanSupplier prizeOn = () -> false;
    private Runnable onTogglePrize = () -> { };

    public void setPrize(java.util.function.BooleanSupplier owned, java.util.function.BooleanSupplier on, Runnable toggle) {
        this.prizeOwned = owned;
        this.prizeOn = on;
        this.onTogglePrize = toggle;
        if (currentPage == Page.VPET) showPage(Page.VPET);
    }

    private Button prizeTile() {
        boolean on = prizeOn.getAsBoolean();
        return tile("★", on ? "#f0c85a" : "#6b737c", on ? "GRANDE: SÍ" : "GRANDE: NO", true, () -> {
            onTogglePrize.run();
            Sounds.play(Sfx.CONFIRMAR);
            showPage(Page.VPET);
        });
    }

    /**
     * Opción SONIDO de la sección V-PET (pedido del usuario): activa o silencia
     * los efectos de sonido y muestra cómo quedó. Se guarda en sonidos.properties.
     */
    private Button soundTile() {
        boolean on = Sounds.isEnabled();
        return tile("♪", on ? "#7ee08a" : "#6b737c", on ? "SONIDO: SÍ" : "SONIDO: NO", true, () -> {
            boolean now = !Sounds.isEnabled();
            Sounds.setEnabled(now);
            if (now) Sounds.play(Sfx.CONFIRMAR); // al activar, el pitido confirma que ya suena
            showPage(Page.VPET);
        });
    }

    private VBox batallaPage() {
        // Batalla aleatoria sigue pidiendo Child o superior; el VS Online acepta cualquier etapa.
        boolean canBattle = stageTier == VPetStageTier.CHILD_PLUS;
        return panel("BATALLA", list(
                        row("⚔", "#d8dde3", "ALEATORIA", canBattle, () -> { popup.hide(); onBattleRandom.run(); }),
                        row("✦", "#ffb35c", "ARENA 2 VS 2", canBattle, () -> { popup.hide(); onArena.run(); }),
                        row("◎", "#8fd6ff", "VS ONLINE", true, () -> { popup.hide(); onVsOnline.run(); })),
                backButton(Page.VPET));
    }

    private VBox movimientoPage() {
        return panel("MOVIMIENTO", list(
                        row("■", "#c9ced6", "QUIETO", true, () -> selectMode(VPetMovementMode.QUIETO)),
                        row("↔", "#8fd6ff", "BARRA", true, () -> selectMode(VPetMovementMode.BARRA)),
                        row("✥", "#8fd6ff", "LIBRE", true, () -> selectMode(VPetMovementMode.ABSOLUTO_LIBRE))),
                backButton(Page.VPET));
    }

    private void selectMode(VPetMovementMode mode) {
        companion.setMode(mode);
        popup.hide();
    }

    // ---------------------------------------------------------------------
    // Piezas visuales

    private VBox panel(String title, Node body, Node footer) {
        Label titleLabel = new Label(title);
        titleLabel.setTextFill(TITLE_COLOR);
        titleLabel.setFont(TITLE_FONT);

        VBox panel = new VBox(8, titleLabel, separator(), body, separator(), footer);
        panel.setPadding(new Insets(10, 12, 10, 12));
        panel.setPrefWidth(PANEL_WIDTH);
        panel.setMaxWidth(PANEL_WIDTH);
        panel.setStyle("-fx-background-color: " + PANEL_BG + "; -fx-background-radius: 8; "
                + "-fx-border-color: " + PANEL_BORDER + "; -fx-border-radius: 8; -fx-border-width: 1.5;");
        return panel;
    }

    /** Flechita hacia el Digimon, del lado donde está. */
    private Node withArrow(VBox panel) {
        Polygon arrow = arrowOnLeft
                ? new Polygon(ARROW_SIZE, 0, 0, ARROW_SIZE, ARROW_SIZE, ARROW_SIZE * 2)
                : new Polygon(0, 0, ARROW_SIZE, ARROW_SIZE, 0, ARROW_SIZE * 2);
        arrow.setFill(Color.web(PANEL_BG));
        arrow.setStroke(Color.web(PANEL_BORDER));
        arrow.setTranslateX(arrowOnLeft ? 1.5 : -1.5); // tapa el borde del panel en la unión
        HBox box = arrowOnLeft ? new HBox(arrow, panel) : new HBox(panel, arrow);
        box.setAlignment(Pos.CENTER);
        // viewOrder (no toFront): toFront reordena los hijos del HBox y mandaba
        // la flecha al lado equivocado. Solo se quiere dibujarla encima del borde.
        arrow.setViewOrder(-1);
        return box;
    }

    private Region separator() {
        Region line = new Region();
        line.setPrefHeight(2);
        line.setMaxWidth(Double.MAX_VALUE);
        line.setStyle("-fx-background-color: linear-gradient(to right, #6fd3e0 0, #6fd3e0 4, #2f5f66 4, "
                + "#2f5f66 96%, #6fd3e0 96%);");
        return line;
    }

    /** Casilla de la página V-PET: ícono arriba, nombre abajo. */
    private Button tile(String icon, String iconColor, String text, boolean enabled, Runnable action) {
        Label iconLabel = new Label(icon);
        iconLabel.setFont(Font.font("Segoe UI Symbol", FontWeight.BOLD, 22));
        iconLabel.setTextFill(Color.web(iconColor));
        Button b = baseButton(text, enabled, action);
        b.setGraphic(iconLabel);
        b.setContentDisplay(ContentDisplay.TOP);
        b.setPrefWidth((PANEL_WIDTH - 24 - 6) / 2);
        b.setPrefHeight(64);
        return b;
    }

    /** Fila de las demás páginas: ícono a la izquierda, texto a la derecha. */
    private Button row(String icon, String iconColor, String text, boolean enabled, Runnable action) {
        Label iconLabel = new Label(icon);
        iconLabel.setFont(Font.font("Segoe UI Symbol", FontWeight.BOLD, 15));
        iconLabel.setTextFill(Color.web(iconColor));
        iconLabel.setMinWidth(24);
        iconLabel.setAlignment(Pos.CENTER);
        Button b = baseButton(text, enabled, action);
        b.setGraphic(iconLabel);
        b.setGraphicTextGap(8);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private Button baseButton(String text, boolean enabled, Runnable action) {
        Button b = new Button(text);
        b.setFont(ITEM_FONT);
        b.setTextFill(Color.WHITE);
        String normal = "-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;";
        String hover = "-fx-background-color: " + HOVER_BG + "; -fx-background-radius: 6; -fx-cursor: hand;";
        b.setStyle(normal);
        b.setOnMouseEntered(e -> b.setStyle(hover));
        b.setOnMouseExited(e -> b.setStyle(normal));
        b.setDisable(!enabled);
        b.setOnAction(e -> {
            Sounds.play(Sfx.CONFIRMAR);
            action.run();
        });
        return b;
    }

    private Button pillButton(String text, Runnable action) {
        return pillButton(text, action, Sfx.CONFIRMAR);
    }

    private Button pillButton(String text, Runnable action, Sfx sound) {
        Button b = new Button(text);
        b.setFont(ITEM_FONT);
        b.setTextFill(Color.WHITE);
        String normal = "-fx-background-color: #1d2024; -fx-background-radius: 6; -fx-border-color: #5a6470; "
                + "-fx-border-radius: 6; -fx-cursor: hand; -fx-padding: 3 10 3 10;";
        String hover = normal.replace("#1d2024", HOVER_BG);
        b.setStyle(normal);
        b.setOnMouseEntered(e -> b.setStyle(hover));
        b.setOnMouseExited(e -> b.setStyle(normal));
        b.setOnAction(e -> {
            Sounds.play(sound);
            action.run();
        });
        return b;
    }

    private Button backButton(Page target) {
        return pillButton("‹ VOLVER", () -> showPage(target), Sfx.CANCELAR);
    }

    private VBox list(Node... items) {
        VBox box = new VBox(2, items);
        box.setFillWidth(true);
        return box;
    }

    private HBox rightAligned(Node n) {
        HBox box = new HBox(n);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private HBox leftAligned(Node n) {
        HBox box = new HBox(n);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private Label smallLabel(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web("#aab3bc"));
        l.setFont(SMALL_FONT);
        l.setWrapText(true);
        return l;
    }
}
