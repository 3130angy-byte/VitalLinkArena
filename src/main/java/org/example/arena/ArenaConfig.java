package org.example.arena;

import org.example.chat.AppPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/**
 * Reglas de la ARENA (modo 2 vs 2 inspirado en la app Vital Bracelet Arena).
 * Las fórmulas exactas de la app nunca se publicaron: todos estos números son
 * NUESTROS y provisionales, así que viven en
 * D:\DigimonProjectData\config\arena.properties (se crea la primera vez) y
 * se pueden ajustar sin recompilar (regla del proyecto).
 *
 * Reglas v2 (2026-10-01, a partir de videos de la app y decisiones del
 * usuario): el ataque es un COMBO por tiempo (5 números a la vista, el que
 * aciertas lo reemplaza el siguiente); el combo da un BONO DE AP con tope; con
 * 10 o más sale el BIG ATTACK; NO hay fallos: el DP funciona como "BP", que
 * sube o baja el daño (misma fórmula del VB: DP + atributo); GUTS = a veces
 * un golpe de KO deja al Digimon con 1 HP.
 */
public final class ArenaConfig {

    static final Path FILE = AppPaths.config().resolve("arena.properties");

    /** Cuántos números se ven A LA VEZ (al acertar uno, aparece el siguiente). */
    public final int attackNumbers;
    /** Velocidad de los círculos del ataque (se desplazan y rebotan en el recuadro): alturas del recuadro por segundo; 0 = quietos. */
    public final double attackCircleSpeed;
    /** Tiempo del minijuego de números (el combo es lo que alcances a encadenar). */
    public final double comboSeconds;
    /** Con este combo o más sale el BIG ATTACK (los 10 círculos del contador llenos, decisión del usuario). */
    public final int comboForBig;
    /** Bono de AP por combo = máximo x (1 - e^(-combo / curva)): 13 de combo ≈ +36 %, 18 ≈ +38 % (como en el video). */
    public final double comboBonusMax;
    public final double comboBonusCurve;
    /** Tope del combo (el servidor de la ARENA online no acepta más). */
    public final int comboMax;
    /** Segundos que se pierden al tocar un número fuera de orden. */
    public final double comboWrongPenalty;
    /**
     * Daño = AP (ya x150) x esto x (1 + bono de combo) x BP x defensa, contra
     * HP ya x400 (ArenaFighter). 1.33 = el mismo balance que se simuló con los
     * stats sin convertir (0.5 x 400 / 150).
     */
    public final double damageFactor;
    /** Cuánto pesa el "BP" (DP + atributo, fórmula del VB): 1 = de x0.5 a x1.5; 0 = no influye. */
    public final double bpWeight;
    /** Probabilidad de GUTS: un golpe que lo dejaría KO lo deja con 1 HP. */
    public final double gutsChance;
    /** Barra de defensa: vueltas completas (ida y vuelta) por segundo y tiempo máximo para detenerla. */
    public final double defenseSweepsPerSecond;
    public final double defenseSeconds;
    /** Distancia al centro (0 = centro exacto, 1 = borde) y el daño que deja pasar cada zona. */
    public final double perfectZone;
    public final double goodZone;
    public final double perfectFactor;
    public final double goodFactor;
    /** Medidor del W-ATTACK (0-100): cuánto sube por cada número del combo y por % de vida perdida. */
    public final double gaugePerCombo;
    public final double gaugePerHpPercentLost;
    /** Daño del W-ATTACK = (AP de los dos compañeros) x esto x daño base (los dos atacan). */
    public final double wAttackMultiplier;
    /** Combo de la máquina: al azar entre estos dos valores. */
    public final int cpuComboLow;
    public final int cpuComboHigh;
    /** Diseño de la pantalla: true = "celular" (estilo VB Arena: sin fondo, ventana casi cuadrada); false = "coliseo" (el anterior). */
    public final boolean phoneLayout;

    private ArenaConfig(Properties p) {
        attackNumbers = (int) Math.max(1, Math.min(9, dbl(p, "ataque.numeros", 5)));
        attackCircleSpeed = Math.max(0, dbl(p, "ataque.velocidad", 0.35));
        comboSeconds = Math.max(2, dbl(p, "combo.segundos", 10));
        comboForBig = (int) Math.max(1, dbl(p, "combo.paraBig", 10));
        comboBonusMax = Math.max(0, dbl(p, "combo.bonoMaximo", 0.40));
        comboBonusCurve = Math.max(0.5, dbl(p, "combo.curva", 5.65));
        comboMax = (int) Math.max(comboForBig, dbl(p, "combo.maximo", 99));
        comboWrongPenalty = Math.max(0, dbl(p, "combo.penalizacionError", 1.0));
        damageFactor = Math.max(0.01, dbl(p, "dano.factor", 1.33));
        bpWeight = Math.max(0, dbl(p, "bp.peso", 1.0));
        gutsChance = Math.max(0, Math.min(1, dbl(p, "guts.probabilidad", 0.10)));
        defenseSweepsPerSecond = dbl(p, "defensa.vueltasPorSegundo", 0.8);
        defenseSeconds = dbl(p, "defensa.segundosMax", 4);
        perfectZone = dbl(p, "defensa.zonaPerfecta", 0.12);
        goodZone = dbl(p, "defensa.zonaBuena", 0.35);
        perfectFactor = dbl(p, "defensa.danoPerfecta", 0.4);
        goodFactor = dbl(p, "defensa.danoBuena", 0.7);
        gaugePerCombo = dbl(p, "wattack.porCombo", 2.5);
        gaugePerHpPercentLost = dbl(p, "wattack.porVidaPerdida", 0.8);
        wAttackMultiplier = dbl(p, "wattack.multiplicador", 2.0);
        cpuComboLow = (int) Math.max(0, dbl(p, "maquina.comboBajo", 6));
        cpuComboHigh = (int) Math.max(cpuComboLow, dbl(p, "maquina.comboAlto", 16));
        phoneLayout = !"coliseo".equalsIgnoreCase(p.getProperty("pantalla.estilo", "celular").trim());
    }

    public static ArenaConfig load() {
        Properties p = new Properties();
        if (!Files.exists(FILE)) writeDefaults();
        else appendMissingRules();
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            System.out.println("[ARENA] No se pudo leer " + FILE + ", valores por defecto: " + e.getMessage());
        }
        return new ArenaConfig(p);
    }

    private static final String RULES_V2 = """
            # --- Reglas v2: combo por tiempo, BIG ATTACK, BP y GUTS ---
            # Minijuego de números: segundos, combo para el BIG ATTACK (10 círculos) y tope.
            combo.segundos=10
            combo.paraBig=10
            combo.maximo=99
            # Segundos que se pierden al tocar un número fuera de orden.
            combo.penalizacionError=1.0
            # Bono de AP del combo = bonoMaximo x (1 - e^(-combo/curva)).
            combo.bonoMaximo=0.40
            combo.curva=5.65
            # Peso del BP (DP + atributo, fórmula del VB): 1 = de x0.5 a x1.5; 0 = no influye.
            bp.peso=1.0
            # GUTS: probabilidad de que un golpe de KO lo deje con 1 HP.
            guts.probabilidad=0.10
            # El medidor del W-ATTACK sube esto por cada número del combo.
            wattack.porCombo=2.5
            # Combo de la máquina, al azar entre estos valores.
            maquina.comboBajo=6
            maquina.comboAlto=16
            """;

    private static final String RULES_V3 = """
            # --- Reglas v3: stats convertidos como la app (DP x120 = BP, HP x400, AP x150) ---
            # Daño = AP x factor x (1 + bono de combo) x BP x defensa. No hay fallos.
            # 1.33 = el mismo balance que antes de convertir los stats.
            dano.factor=1.33
            """;

    private static void writeDefaults() {
        String text = """
                # ARENA (2 vs 2). Reglas PROPIAS y provisionales: la app oficial nunca publicó las suyas.
                # Los stats se convierten como la app (fijo): DP x120 = BP, HP x400, AP x150.
                # Números a la vista a la vez (al acertar uno aparece el siguiente).
                ataque.numeros=5
                # Los círculos se desplazan dentro del recuadro y rebotan (como en Vital Bracelet Arena):
                # alturas del recuadro por segundo. 0 = quietos.
                ataque.velocidad=0.35
                # Defensa: detener la barra en el centro. Velocidad, tiempo máximo y zonas (0 = centro, 1 = borde).
                defensa.vueltasPorSegundo=0.8
                defensa.segundosMax=4
                defensa.zonaPerfecta=0.12
                defensa.zonaBuena=0.35
                # Daño que deja pasar cada zona (1 = todo).
                defensa.danoPerfecta=0.4
                defensa.danoBuena=0.7
                # W-ATTACK: el medidor (0-100) también sube por % de vida perdida.
                wattack.porVidaPerdida=0.8
                # Daño del W-ATTACK = AP de los dos compañeros x esto x dano.factor.
                wattack.multiplicador=2.0
                # Diseño de la pantalla: celular (estilo VB Arena, sin fondo) o coliseo (el anterior).
                pantalla.estilo=celular
                """ + RULES_V2 + RULES_V3;
        try {
            Files.writeString(FILE, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[ARENA] No se pudo crear " + FILE + ": " + e.getMessage());
        }
    }

    /** Un arena.properties de antes no trae las claves nuevas: se agregan al final (las viejas quedan, ya no se usan). */
    private static void appendMissingRules() {
        try {
            String current = Files.readString(FILE, StandardCharsets.UTF_8);
            String add = (current.contains("combo.segundos") ? "" : RULES_V2)
                    + (current.contains("dano.factor") ? "" : RULES_V3);
            if (add.isEmpty()) return;
            Files.writeString(FILE, (current.endsWith("\n") ? "" : "\n") + add,
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[ARENA] No se pudieron agregar las reglas nuevas a " + FILE + ": " + e.getMessage());
        }
    }

    private static double dbl(Properties p, String key, double def) {
        try {
            return Double.parseDouble(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
