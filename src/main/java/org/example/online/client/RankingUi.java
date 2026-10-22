package org.example.online.client;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import org.example.online.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Supplier;

/**
 * Tabla de posiciones de la sala (protocolo v13, conjunto 4 de la actualización conjunta): al llegar a la terminal
 * se abre el ranking del mes, uno por tipo de pelea (Original, Libre y ARENA 2 vs 2). Lo arma el servidor
 * (Ranking): aquí solo se muestra. Tus Digimon van resaltados.
 */
final class RankingUi {

    final StackPane layer = new StackPane();

    private final Supplier<VsClient> client;
    private VBox box;
    private final VBox rowsBox = new VBox(3);
    private final Label header = new Label();
    private final Label rules = new Label();
    private final Button original = new Button("ORIGINAL"), free = new Button("LIBRE"), arena = new Button("ARENA 2 VS 2");
    private String mode = Protocol.MODE_ORIGINAL;
    private boolean near;

    RankingUi(Supplier<VsClient> client) {
        this.client = client;
        layer.setPickOnBounds(false);
    }

    void setNear(boolean nowNear) {
        if (nowNear && !near) open();
        if (!nowNear && near) close();
        near = nowNear;
    }

    boolean handle(JSONObject m) {
        if (!"rankList".equals(m.optString("t"))) return false;
        if (mode.equals(m.optString("mode"))) fill(m);
        return true;
    }

    private void open() {
        if (box == null) {
            Label title = new Label("TABLA DE POSICIONES");
            title.setStyle(OfficialBattleUi.TEXT_STYLE + " -fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #ffd246;");
            header.setStyle("-fx-text-fill: #aab3bc; -fx-font-size: 11px;");
            rules.setStyle("-fx-text-fill: #8c96a2; -fx-font-size: 10px;");
            rules.setWrapText(true);
            for (Button b : new Button[]{original, free, arena}) {
                b.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(b, Priority.ALWAYS);
            }
            original.setOnAction(e -> select(Protocol.MODE_ORIGINAL));
            free.setOnAction(e -> select(Protocol.MODE_FREE));
            arena.setOnAction(e -> select(Protocol.MODE_ARENA));
            ScrollPane scroll = new ScrollPane(rowsBox);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(250);
            scroll.setStyle("-fx-background: #0c162c; -fx-background-color: #0c162c;");
            Button close = new Button("Cerrar");
            close.setStyle(OfficialBattleUi.BUTTON_STYLE);
            close.setOnAction(e -> close());
            box = new VBox(8, title, new HBox(6, original, free, arena), header, scroll, rules, close);
            box.setPadding(new Insets(12));
            box.setMaxWidth(400);
            box.setMaxHeight(Region.USE_PREF_SIZE);
            box.setStyle(OfficialBattleUi.PANEL_STYLE);
            StackPane.setAlignment(box, Pos.TOP_CENTER);
            StackPane.setMargin(box, new Insets(90, 0, 0, 0));
        }
        if (!layer.getChildren().contains(box)) layer.getChildren().add(box);
        select(mode);
    }

    private void close() {
        if (box != null) layer.getChildren().remove(box);
    }

    private void select(String newMode) {
        mode = newMode;
        original.setStyle(Protocol.MODE_ORIGINAL.equals(mode) ? OfficialBattleUi.SELECTED_STYLE : OfficialBattleUi.BUTTON_STYLE);
        free.setStyle(Protocol.MODE_FREE.equals(mode) ? OfficialBattleUi.SELECTED_STYLE : OfficialBattleUi.BUTTON_STYLE);
        arena.setStyle(Protocol.MODE_ARENA.equals(mode) ? OfficialBattleUi.SELECTED_STYLE : OfficialBattleUi.BUTTON_STYLE);
        rowsBox.getChildren().setAll(note("Cargando..."));
        client.get().rankingList(mode);
    }

    private void fill(JSONObject m) {
        header.setText(Protocol.modeName(mode) + "  ·  mes " + m.optString("month")
                + (Protocol.MODE_ARENA.equals(mode) ? "  ·  la victoria es del puesto 1 del equipo" : ""));
        rules.setText("Cuentan los retos del NPC Batalla oficial y los combates de torneo (los empates no). La tabla"
                + " se reinicia cada mes; sale el Digimon que no entra a la sala en " + m.optInt("days", 14)
                + " días o que muere, y las copias del LAB solo figuran el día en que pelearon.");
        rowsBox.getChildren().clear();
        JSONArray rows = m.optJSONArray("rows");
        if (rows == null || rows.length() == 0) {
            rowsBox.getChildren().add(note("Todavía nadie ganó una pelea este mes en este modo."));
            return;
        }
        int lastPos = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            int pos = r.optInt("pos");
            if (pos > lastPos + 1 && lastPos > 0) rowsBox.getChildren().add(note("..."));
            lastPos = pos;
            String medal = pos + ".";
            Label who = new Label(medal + "  " + r.optString("species") + (r.optBoolean("copy") ? " (copia)" : "")
                    + "  —  " + r.optString("owner"));
            Label score = new Label(r.optInt("wins") + " V  ·  " + r.optInt("losses") + " D");
            boolean mine = r.optBoolean("mine");
            // Tus Digimon en amarillo; los 3 primeros con su color de podio (oro, plata, bronce).
            String color = mine ? "#ffd246" : switch (pos) {
                case 1 -> "#ffe48a";
                case 2 -> "#d8dee8";
                case 3 -> "#e0a070";
                default -> "white";
            };
            who.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;" + (r.optBoolean("current") ? " -fx-font-weight: bold;" : ""));
            score.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px; -fx-font-weight: bold;");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox row = new HBox(6, who, gap, score);
            row.setAlignment(Pos.CENTER_LEFT);
            rowsBox.getChildren().add(row);
        }
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #aab3bc; -fx-font-size: 11px;");
        return l;
    }
}
