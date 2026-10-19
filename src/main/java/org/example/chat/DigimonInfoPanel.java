package org.example.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Window;

import org.example.battle.DigimonRank;
import org.example.battle.PowerTrophyBonus;

/**
 * Panel "Digimon", diseño calcado del dispositivo real. El nombre se
 * muestra como la imagen real del sprite NAME (nunca convertido a texto),
 * con scroll si es más ancho que el panel -- igual que hace DIM-Modifier.
 */
public class DigimonInfoPanel {

    private static final double PANEL_WIDTH = 180;
    private static final double BANNER_HEIGHT = 22;
    private static final double PORTRAIT_SIZE = 140;

    private final Window ownerWindow;
    private final Popup popup = new Popup();
    private NameSpriteBanner nameBanner;

    public DigimonInfoPanel(Window ownerWindow) {
        this.ownerWindow = ownerWindow;
    }

    /** Stats de la DIM + puntos (Power Trophies / trofeos) para la sección de batalla del panel. */
    public record BattleStats(int dp, int hp, int ap, int points) {}

    public void show(double preferredX, double preferredY, Image nameSpriteImage, Image portraitImage,
                     String attributeLabel, String activityTypeLabel, long daysSinceBirth, BattleStats stats) {

        nameBanner = new NameSpriteBanner(nameSpriteImage, PANEL_WIDTH, BANNER_HEIGHT);

        Label typeLabel = pixelStatLine("TYPE " + attributeLabel.toUpperCase());
        Label ageLabel = pixelStatLine("AGE " + daysSinceBirth);
        Label activityLabel = pixelStatLine(activityTypeLabel.toUpperCase());

        ImageView portraitView = new ImageView(portraitImage);
        portraitView.setFitWidth(PORTRAIT_SIZE);
        portraitView.setFitHeight(PORTRAIT_SIZE);
        portraitView.setPreserveRatio(true);
        portraitView.setSmooth(false);

        VBox statsBox = new VBox(2, typeLabel, ageLabel, activityLabel);
        statsBox.setPadding(new Insets(6, 0, 6, 8));

        VBox content = new VBox(nameBanner, statsBox, portraitView, battleSection(stats));
        content.setAlignment(Pos.TOP_CENTER);
        content.setPrefWidth(PANEL_WIDTH);

        StackPane screen = new StackPane(content);
        screen.setPrefWidth(PANEL_WIDTH);
        screen.setStyle("-fx-border-color: #1a1a1a; -fx-border-width: 4; -fx-border-radius: 6;");

        popup.getContent().setAll(screen);
        popup.setAutoHide(true);
        popup.setOnHidden(e -> { if (nameBanner != null) nameBanner.stop(); });

        screen.applyCss();
        screen.layout();
        // El fondo se mide DESPUÉS del contenido y ya dentro de la escena del popup
        // (la sección de batalla cambia de alto; fuera de escena las fuentes no miden bien).
        Canvas gridBackground = buildGridBackground(PANEL_WIDTH, content.prefHeight(PANEL_WIDTH));
        StackPane.setAlignment(gridBackground, Pos.TOP_CENTER);
        screen.getChildren().add(0, gridBackground);
        screen.layout();
        double panelWidth = screen.prefWidth(-1);
        double panelHeight = screen.prefHeight(-1);

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double x = clamp(preferredX, bounds.getMinX(), bounds.getMaxX() - panelWidth);
        double y = clamp(preferredY, bounds.getMinY(), bounds.getMaxY() - panelHeight);

        popup.show(ownerWindow, x, y);
    }

    /**
     * Rango, puntos y stats: base de la DIM + lo que suma el bono (cada 10
     * puntos: +50% DP, +25% HP, +1 AP; tope 120). El bono solo se usa en
     * la Batalla Libre del VS Online (decisión del usuario).
     */
    private VBox battleSection(BattleStats stats) {
        DigimonRank ranks = DigimonRank.load();
        int points = stats.points();
        String rank = ranks.rankFor(points);
        PowerTrophyBonus bonus = PowerTrophyBonus.forTrophies(points, stats.dp(), stats.hp());

        VBox box = new VBox(2);
        box.setPadding(new Insets(6, 0, 8, 8));
        box.getChildren().add(pixelStatLine(DigimonRank.NO_RANK.equals(rank) ? "SIN RANGO" : "RANGO " + rank));
        box.getChildren().add(pixelStatLine("PUNTOS " + points
                + (points > PowerTrophyBonus.MAX_TROPHIES ? " (TOPE " + PowerTrophyBonus.MAX_TROPHIES + ")" : "")));
        int next = ranks.nextThreshold(points);
        Label nextLine = smallLine(next < 0 ? "Rango máximo." : "Siguiente rango en " + next + " puntos.");
        box.getChildren().add(nextLine);

        box.getChildren().add(pixelStatLine("     BASE  BONO"));
        box.getChildren().add(pixelStatLine(statRow("DP", stats.dp(), bonus.dpBonus)));
        box.getChildren().add(pixelStatLine(statRow("HP", stats.hp(), bonus.hpBonus)));
        box.getChildren().add(pixelStatLine(statRow("AP", stats.ap(), bonus.apBonus)));
        box.getChildren().add(smallLine("El bono solo cuenta en la Batalla Libre del VS Online."));
        return box;
    }

    /** "DP    50   +75": columnas fijas (fuente monoespaciada). */
    private static String statRow(String name, int base, int bonus) {
        return String.format("%-2s %6d %5s", name, base, "+" + bonus);
    }

    private Label smallLine(String text) {
        Label label = new Label(text);
        label.setTextFill(Color.WHITE);
        label.setFont(Font.font("Consolas", 10));
        label.setWrapText(true);
        label.setMaxWidth(PANEL_WIDTH - 14);
        return label;
    }

    private Label pixelStatLine(String text) {
        Label label = new Label(text);
        label.setTextFill(Color.WHITE);
        label.setFont(Font.font("Consolas", FontWeight.BOLD, 13));
        return label;
    }

    private Canvas buildGridBackground(double width, double height) {
        Canvas canvas = new Canvas(width, height);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(80, 225, 235)),
                new Stop(1, Color.rgb(20, 140, 170))));
        gc.fillRect(0, 0, width, height);

        gc.setStroke(Color.rgb(255, 255, 255, 0.25));
        gc.setLineWidth(1);
        double step = 10;
        for (double x = 0; x <= width; x += step) gc.strokeLine(x, 0, x, height);
        for (double y = 0; y <= height; y += step) gc.strokeLine(0, y, width, y);

        return canvas;
    }

    private double clamp(double value, double min, double max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }
}