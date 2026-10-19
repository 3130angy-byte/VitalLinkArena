package org.example.behavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Los Digimon del escritorio. El ORDEN de la lista es el PUESTO (decisión del
 * usuario, como en Vital Bracelet Arena): índice 0 = puesto 1 (principal:
 * pelea las batallas tipo VB), índice 1 = puesto 2 (secundario: solo cuenta
 * en el 2 vs 2). Se cambia desde el Laboratorio.
 */
public class DigimonRegistry {

    private final List<DigimonInstance> activeInstances = new ArrayList<>();

    public void add(DigimonInstance instance) {
        activeInstances.add(instance);
    }

    /** Inserta {@code instance} en el puesto {@code index} (0 o 1); quien estaba ahí pasa al siguiente. */
    public void putAt(int index, DigimonInstance instance) {
        activeInstances.remove(instance);
        activeInstances.add(Math.max(0, Math.min(index, activeInstances.size())), instance);
    }

    /** Quita una instancia del registro (al retirarla, reemplazarla o si falló al aparecer). */
    public void remove(String instanceId) {
        activeInstances.removeIf(i -> i.getInstanceId().equals(instanceId));
    }

    /** Intercambia el puesto 1 y el 2. */
    public void swapOrder() {
        if (activeInstances.size() == 2) Collections.swap(activeInstances, 0, 1);
    }

    public List<DigimonInstance> getActiveInstances() {
        return activeInstances;
    }

    /** Puesto 1 (principal), si hay alguien en el escritorio. */
    public Optional<DigimonInstance> primary() {
        return activeInstances.isEmpty() ? Optional.empty() : Optional.of(activeInstances.get(0));
    }

    /** Puesto 2 (secundario), si lo hay. */
    public Optional<DigimonInstance> secondary() {
        return activeInstances.size() < 2 ? Optional.empty() : Optional.of(activeInstances.get(1));
    }

    /** El que está en la sala del VS Online (siempre el puesto 1 al entrar), si alguien está ahí. */
    public Optional<DigimonInstance> lobbyMember() {
        return activeInstances.stream().filter(DigimonInstance::isInLobby).findFirst();
    }

    public boolean isEmpty() {
        return activeInstances.isEmpty();
    }

    public Optional<DigimonInstance> getOther(String instanceId) {
        return activeInstances.stream().filter(i -> !i.getInstanceId().equals(instanceId)).findFirst();
    }
}
