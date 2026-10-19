package org.example.chat;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextFlow;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Globo de diálogo del Digimon. Arreglos del 2026-10-05 (pedido del usuario):
 * <ul>
 * <li>Siempre ARRIBA del Digimon y crece hacia arriba, con la cola apuntando a su cabeza; nunca se
 * sale de la pantalla (si arriba no hay lugar, va abajo). Antes se ubicaba desde su borde de arriba
 * y, con mucho texto, crecía hacia abajo y tapaba al Digimon.</li>
 * <li>Sin el recuadro blanco: el panel raíz de la escena tomaba el fondo gris claro del estilo
 * por defecto de JavaFX y la ventana era más grande que el globo.</li>
 * <li>Botón ✕ en la esquina de arriba a la derecha (antes estaba centrado en la esquina de arriba a
 * la izquierda, medio fuera de la ventana, y no se veía).</li>
 * <li>Mensajes largos en PARTES: se cortan al final de una frase y sale otro globo con lo que sigue;
 * un ▶ avisa que hay más. El ✕ cierra todas.</li>
 * </ul>
 */
public class SpeechBubble {

    private static final double MAX_TEXT_WIDTH = 230;
    /** Tope de caracteres por parte del mensaje (~4 líneas): más largo, sigue en otro globo. */
    private static final int MAX_PAGE_CHARS = 150;
    private static final double PADDING = 12;
    private static final double TAIL_HEIGHT = 14;
    private static final double TAIL_WIDTH = 16;
    private static final double CLOSE_RADIUS = 9;
    /** Espacio arriba y a la derecha del cuerpo para que el ✕ entre entero en la ventana. */
    private static final double MARGIN = CLOSE_RADIUS + 3;
    /** La punta de la cola toca el Digimon un poco por debajo del borde de su ventana. */
    private static final double TAIL_INTO_PET = 6;
    private static final double SECONDS_PER_WORD = 0.45;
    private static final double MIN_DISPLAY_SECONDS = 3.0;
    private static final double MAX_DISPLAY_SECONDS = 14.0;

    private static final Color FILL = Color.rgb(255, 250, 225);
    private static final Color BORDER = Color.rgb(70, 55, 20);
    private static final Color CLOSE_HOVER = Color.rgb(255, 222, 170);
    private static final Color TEXT_COLOR = Color.rgb(40, 30, 10);
    private static final Font TEXT_FONT = Font.font("Comic Sans MS", 12);

    private final Stage bubbleStage;
    private final Pane root;
    private final Rectangle body;
    private final Polygon tail;
    private final Rectangle tailJoin;
    private final Polygon moreMark;
    private final TextFlow textFlow;
    private final Circle closeCircle;
    private final Group closeX;
    private PauseTransition hideTimer;
    private AnimationTimer followTimer;
    private Runnable onBubbleClicked;
    private String digimonName = "";
    private Image nameSpriteImage;

    private double bodyW, bodyH;
    /** true = el globo va debajo del Digimon (arriba no había lugar): la cola sale por arriba. */
    private boolean below;
    private List<String> pages = List.of();
    private int pageIndex;

    public void setDigimonNameSpriteImage(Image image) {
        this.nameSpriteImage = image;
    }

    public SpeechBubble() {
        root = new Pane();
        // Sin esto el panel raíz toma el fondo gris claro del estilo por defecto: el "recuadro blanco".
        root.setBackground(Background.EMPTY);
        root.setStyle("-fx-background-color: transparent;");

        body = new Rectangle();
        body.setArcWidth(16);
        body.setArcHeight(16);
        body.setFill(FILL);
        body.setStroke(BORDER);
        body.setStrokeWidth(1.5);
        body.setCursor(Cursor.HAND);

        tail = new Polygon();
        tail.setFill(FILL);
        tail.setStroke(BORDER);
        tail.setStrokeWidth(1.5);
        tail.setMouseTransparent(true);

        // Tapa la línea del cuerpo donde nace la cola (si no, se ve un corte entre los dos).
        tailJoin = new Rectangle();
        tailJoin.setFill(FILL);
        tailJoin.setMouseTransparent(true);

        // ▶ abajo a la derecha: el mensaje sigue en otro globo.
        moreMark = new Polygon();
        moreMark.setFill(BORDER);
        moreMark.setMouseTransparent(true);

        textFlow = new TextFlow();
        textFlow.setMaxWidth(MAX_TEXT_WIDTH);
        textFlow.setMouseTransparent(true);

        closeCircle = new Circle(CLOSE_RADIUS, FILL);
        closeCircle.setStroke(BORDER);
        closeCircle.setStrokeWidth(1.5);
        closeCircle.setCursor(Cursor.HAND);
        closeX = new Group(xLine(-3.2, -3.2, 3.2, 3.2), xLine(-3.2, 3.2, 3.2, -3.2));
        closeX.setMouseTransparent(true);
        closeCircle.setOnMouseEntered(e -> closeCircle.setFill(CLOSE_HOVER));
        closeCircle.setOnMouseExited(e -> closeCircle.setFill(FILL));

        root.getChildren().addAll(body, tail, tailJoin, textFlow, moreMark, closeCircle, closeX);

        body.setOnMouseClicked(e -> {
            if (onBubbleClicked != null) onBubbleClicked.run();
        });
        closeCircle.setOnMouseClicked(e -> {
            e.consume();
            hide();
        });

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);

        bubbleStage = new Stage();
        org.example.ui.DesktopLayer.attach(bubbleStage);
        bubbleStage.initStyle(StageStyle.TRANSPARENT);
        bubbleStage.setAlwaysOnTop(true);
        bubbleStage.setScene(scene);
    }

    private static Line xLine(double x1, double y1, double x2, double y2) {
        Line l = new Line(x1, y1, x2, y2);
        l.setStroke(BORDER);
        l.setStrokeWidth(1.8);
        l.setStrokeLineCap(StrokeLineCap.ROUND);
        return l;
    }

    public void setOnBubbleClicked(Runnable callback) {
        this.onBubbleClicked = callback;
    }

    public void setDigimonName(String name) {
        this.digimonName = name != null ? name : "";
    }

    /** Muestra el mensaje (en partes si es largo) sobre petStage, siguiéndolo mientras esté visible. */
    public void show(String text, Stage petStage) {
        if (text == null || text.isBlank()) return;
        pages = paginate(text.trim(), MAX_PAGE_CHARS);
        pageIndex = 0;
        showPage(petStage);
    }

    private void showPage(Stage petStage) {
        if (hideTimer != null) hideTimer.stop();
        if (followTimer != null) followTimer.stop();
        String text = pages.get(pageIndex);
        boolean more = pageIndex < pages.size() - 1;

        textFlow.getChildren().setAll(NameBadge.buildInlineNodesWithSprite(text, digimonName, nameSpriteImage, TEXT_COLOR, TEXT_FONT));
        root.applyCss();
        root.layout();
        double textW = Math.max(textFlow.prefWidth(-1), 30);
        textW = Math.min(textW, MAX_TEXT_WIDTH);
        double textH = Math.max(textFlow.prefHeight(textW), 16);
        textFlow.resize(textW, textH);
        bodyW = textW + PADDING * 2;
        bodyH = textH + PADDING * 2 + (more ? 4 : 0);
        moreMark.setVisible(more);

        below = !fitsAbove(petStage);
        layoutParts();
        bubbleStage.setWidth(bodyW + MARGIN);
        bubbleStage.setHeight(MARGIN + bodyH + TAIL_HEIGHT);
        repositionOver(petStage);
        bubbleStage.show();

        followTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                repositionOver(petStage);
            }
        };
        followTimer.start();

        int wordCount = Math.max(1, text.split("\\s+").length);
        double seconds = clamp(wordCount * SECONDS_PER_WORD, MIN_DISPLAY_SECONDS, MAX_DISPLAY_SECONDS);
        hideTimer = new PauseTransition(Duration.seconds(seconds));
        hideTimer.setOnFinished(e -> {
            if (pageIndex < pages.size() - 1) {
                pageIndex++;
                showPage(petStage);
            } else {
                hide();
            }
        });
        hideTimer.play();
    }

    /** Cuerpo, texto, ✕ y marca de "sigue" según el globo vaya arriba (cola abajo) o abajo (cola arriba). */
    private void layoutParts() {
        double bodyY = below ? MARGIN + TAIL_HEIGHT : MARGIN;
        body.setX(0);
        body.setY(bodyY);
        body.setWidth(bodyW);
        body.setHeight(bodyH);
        textFlow.relocate(PADDING, bodyY + PADDING);
        closeCircle.setCenterX(bodyW - 2);
        closeCircle.setCenterY(bodyY + 2);
        closeX.setLayoutX(bodyW - 2);
        closeX.setLayoutY(bodyY + 2);
        double mx = bodyW - 13, my = bodyY + bodyH - 10;
        moreMark.getPoints().setAll(mx, my, mx + 6, my + 3.5, mx, my + 7);
    }

    private boolean fitsAbove(Stage petStage) {
        Rectangle2D screen = screenOf(petStage);
        return petStage.getY() + TAIL_INTO_PET - (MARGIN + bodyH + TAIL_HEIGHT) >= screen.getMinY();
    }

    /** Arriba (o abajo) del Digimon, dentro de la pantalla, con la cola apuntando a su centro. */
    private void repositionOver(Stage petStage) {
        Rectangle2D screen = screenOf(petStage);
        double petCenterX = petStage.getX() + petStage.getWidth() / 2;
        double windowW = bodyW + MARGIN, windowH = MARGIN + bodyH + TAIL_HEIGHT;
        double x = clamp(petCenterX - bodyW * 0.3, screen.getMinX(), screen.getMaxX() - windowW);
        double y = below
                ? petStage.getY() + petStage.getHeight() - TAIL_INTO_PET - MARGIN
                : petStage.getY() + TAIL_INTO_PET - windowH;
        bubbleStage.setX(x);
        bubbleStage.setY(y);

        // La cola nace un poco dentro del cuerpo (el parche tapa esa unión) y no llega a la zona del ✕.
        double tipX = clamp(petCenterX - x, 14, bodyW - 26);
        double baseX = clamp(tipX + 4, 14, bodyW - 26);
        double half = TAIL_WIDTH / 2, join = TAIL_WIDTH - 6;
        if (below) {
            double edge = MARGIN + TAIL_HEIGHT, baseY = edge + 3;
            tail.getPoints().setAll(baseX - half, baseY, baseX + half, baseY, tipX, MARGIN);
            tailJoin.setX(baseX - join / 2);
            tailJoin.setY(edge - 1);
        } else {
            double edge = MARGIN + bodyH, baseY = edge - 3;
            tail.getPoints().setAll(baseX - half, baseY, baseX + half, baseY, tipX, edge + TAIL_HEIGHT);
            tailJoin.setX(baseX - join / 2);
            tailJoin.setY(baseY - 1);
        }
        tailJoin.setWidth(join);
        tailJoin.setHeight(5);
    }

    private static Rectangle2D screenOf(Stage petStage) {
        List<Screen> screens = Screen.getScreensForRectangle(petStage.getX(), petStage.getY(),
                Math.max(1, petStage.getWidth()), Math.max(1, petStage.getHeight()));
        return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
    }

    public boolean isShowing() { return bubbleStage.isShowing(); }

    public void hide() {
        if (hideTimer != null) hideTimer.stop();
        if (followTimer != null) followTimer.stop();
        pages = List.of();
        bubbleStage.hide();
    }

    /**
     * Parte el mensaje en trozos de hasta {@code maxChars}, siempre al final de una frase. Una frase más
     * larga que eso se corta en la última coma o espacio, con "…" para que se note que sigue.
     */
    static List<String> paginate(String text, int maxChars) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (".!?…".indexOf(text.charAt(i)) < 0) continue;
            int end = i;
            while (end + 1 < text.length() && ".!?…\"»)".indexOf(text.charAt(end + 1)) >= 0) end++;
            if (end + 1 < text.length() && !Character.isWhitespace(text.charAt(end + 1))) continue; // "3.5", "Sr.X"
            sentences.add(text.substring(start, end + 1).trim());
            start = end + 1;
            i = end;
        }
        if (start < text.length() && !text.substring(start).isBlank()) sentences.add(text.substring(start).trim());

        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String s : sentences) {
            if (page.length() > 0 && page.length() + 1 + s.length() > maxChars) {
                pages.add(page.toString());
                page.setLength(0);
            }
            while (s.length() > maxChars) {
                int cut = Math.max(s.lastIndexOf(", ", maxChars - 2) + 1, s.lastIndexOf(' ', maxChars - 2));
                if (cut <= maxChars / 3) cut = maxChars - 2;
                if (page.length() > 0) {
                    pages.add(page.toString());
                    page.setLength(0);
                }
                pages.add(s.substring(0, cut).trim() + "…");
                s = s.substring(cut).trim();
            }
            if (page.length() > 0) page.append(' ');
            page.append(s);
        }
        if (page.length() > 0) pages.add(page.toString());
        if (pages.isEmpty()) pages.add(text);
        return pages;
    }

    private static double clamp(double v, double min, double max) {
        return max < min ? min : Math.max(min, Math.min(max, v));
    }
}
