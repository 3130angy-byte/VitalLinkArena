package org.example.lab;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * El equipo del escritorio, visto desde el Laboratorio (lo implementa Main):
 * hasta 2 Digimon, en PUESTO 1 (principal: batallas tipo VB) y PUESTO 2
 * (secundario: solo cuenta en el 2 vs 2). Decisión del usuario: para
 * cambiar a quién está en el escritorio NO hace falta retirar (eso genera
 * el archivo para el VB); se elige a quién reemplazar y el reemplazado
 * vuelve a su cápsula por el portal.
 */
public interface DesktopTeam {

    int MAX = 2;

    /** Quién está en cada puesto (índice 0 = puesto 1). */
    record Member(String capsuleId, String name) {}

    List<Member> members();

    /**
     * Saca la cápsula al escritorio: {@code replaceIndex} = -1 si hay lugar
     * libre, o 0/1 = puesto a reemplazar. Devuelve un error, o null si salió bien.
     */
    String place(LabStorage.Capsule capsule, int replaceIndex);

    /** Intercambia puesto 1 y puesto 2. Devuelve un error, o null si salió bien. */
    String swapOrder();

    /**
     * Si el escritorio está lleno, pregunta a quién reemplazar. Devuelve -1
     * si hay lugar, 0/1 el puesto elegido, o vacío si el usuario canceló.
     */
    static Optional<Integer> askReplace(Window owner, List<Member> members, String newName) {
        if (members.size() < MAX) return Optional.of(-1);
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(owner);
        ask.setTitle("Escritorio lleno");
        ask.setHeaderText("Ya hay " + MAX + " Digimon en el escritorio.");
        ask.setContentText("¿A cuál reemplaza " + newName + "? El reemplazado vuelve a su cápsula del Laboratorio "
                + "(conserva su récord); no se genera ningún archivo.");
        ButtonType first = new ButtonType("Puesto 1: " + members.get(0).name());
        ButtonType second = new ButtonType("Puesto 2: " + members.get(1).name());
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        ask.getButtonTypes().setAll(first, second, cancel);
        ButtonType chosen = ask.showAndWait().orElse(cancel);
        if (chosen == first) return Optional.of(0);
        if (chosen == second) return Optional.of(1);
        return Optional.empty();
    }
}
