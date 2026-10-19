package org.example.chat;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elige la reacción de UN Digimon con las reglas del banco (LEEME.txt y estilo.txt del borrador):
 * [apertura del Activity Type] + [frase del evento según etapa y atributo] + [cierre del Activity Type].
 *
 * - Frases de "primera vez": la primera vez de cada evento en su vida (sección según su etapa); dicha
 *   una, el grupo entero queda bloqueado y se guarda en su cápsula ({@link Memory}).
 * - Nunca repite una de las últimas 5 frases de ese evento.
 * - Estilo con la probabilidad de su Activity Type; en Ultimate la mitad y solo apertura.
 * - Nada se repite: sin apertura si la frase ya empieza con una exclamación, sin apertura ni cierre
 *   que repita una palabra de la frase, y la misma pieza no vuelve en las 3 reacciones siguientes.
 * - Una frase con una variable que no se tiene ({rival} sin especie leída...) no se usa.
 * - El programa pone la mayúscula al inicio de cada oración ("la Calculadora abierta." -> "La...").
 */
public final class PhrasePicker {

    /** Lo que se recuerda entre sesiones (en la cápsula): los grupos de "primera vez" ya usados. */
    public interface Memory {
        Set<String> firstTimeDone();
        void saveFirstTimeDone(Set<String> groups);
    }

    /** Sin persistencia (muestras, pruebas). */
    public static Memory inMemory() {
        Set<String> done = new HashSet<>();
        return new Memory() {
            public Set<String> firstTimeDone() { return done; }
            public void saveFirstTimeDone(Set<String> groups) { done.clear(); done.addAll(groups); }
        };
    }

    private static final String[] TYPES = {"estoico", "activo", "normal", "interior", "perezoso"};
    private static final double[] STYLE_CHANCE = {0.10, 0.40, 0.25, 0.35, 0.30};
    /** La primera derrota es un solo grupo para todos los modos (decisión del usuario, 2026-10-06). */
    private static final Map<String, String> SHARED_FIRST = Map.of("arena_derrota", "derrota");
    /** Eventos que se repiten mucho: a veces se queda callado (nunca en su primera vez). */
    private static final Set<String> MAY_STAY_SILENT = Set.of("victoria", "derrota", "empate", "arena_victoria", "arena_derrota");
    private static final double SILENCE_CHANCE = 0.2;

    private static final Set<String> INTERJECTIONS = Set.of(
            "ja", "je", "jeje", "ji", "jiji", "tsk", "hmph", "hmm", "mm", "mmm", "bah", "uf", "uy", "ay", "uh",
            "uuh", "oh", "ooh", "grr", "snif", "rayos", "diablos", "demonios", "maldicion", "yay", "ups", "ey",
            "oye", "eh", "mira", "vaya", "wooo", "yoh", "ding", "ta-da", "la-la-la", "pi", "tic", "siii",
            "yaaay", "wiii", "argh", "aaah", "uaaah", "uaah", "ah");
    private static final Set<String> STOP_WORDS = Set.of("que", "con", "por", "los", "las", "una", "uno", "del", "sin", "muy");
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]+)}");

    private final PhraseBank bank;
    private final String attr;         // "vacuna", "data", "virus", "free" o null
    private final String stage;        // "nino", "maduro" o "maduro 2"
    private final String styleStage;   // "nino" o "maduro"
    private final int activityType;
    private final Random rnd;
    private final Memory memory;
    private final Map<String, Deque<String>> lastPerEvent = new HashMap<>();
    private final Deque<List<String>> lastStyle = new ArrayDeque<>();
    private boolean forceStyle = false;

    /**
     * @param attribute    de la DIM: 1 Virus, 2 Data, 3 Vaccine, 4 Free (otro = solo frases "todos")
     * @param dimStage     0-5 (Baby se trata como Child: la 0.0.3 no los tiene)
     * @param activityType 0 Stoic, 1 Active, 2 Normal, 3 Indoor, 4 Lazy
     */
    public PhrasePicker(PhraseBank bank, int attribute, int dimStage, int activityType, Random rnd, Memory memory) {
        this.bank = bank;
        this.attr = switch (attribute) {
            case 1 -> "virus";
            case 2 -> "data";
            case 3 -> "vacuna";
            case 4 -> "free";
            default -> null;
        };
        this.stage = dimStage <= 2 ? "nino" : dimStage <= 4 ? "maduro" : "maduro 2";
        this.styleStage = dimStage <= 2 ? "nino" : "maduro";
        this.activityType = Math.max(0, Math.min(4, activityType));
        this.rnd = rnd;
        this.memory = memory;
    }

    /** Muestras para revisar el banco: toda reacción lleva estilo si alguna pieza lo permite. */
    public PhrasePicker forceStyle() {
        this.forceStyle = true;
        return this;
    }

    public boolean isUltimate() { return stage.equals("maduro 2"); }

    /** La reacción armada, o vacío si el banco no tiene una frase usable (o decidió quedarse callado). */
    public Optional<String> pick(String event, Map<String, String> vars) {
        return pickDetailed(event, vars, true).map(Reaction::text);
    }

    /** Siempre responde si hay frase (nunca se queda callado). */
    public Optional<String> pickAlways(String event, Map<String, String> vars) {
        return pickDetailed(event, vars, false).map(Reaction::text);
    }

    /** Una reacción con sus piezas (las muestras marcan qué es estilo). */
    public record Reaction(String text, String opener, String phrase, String closer, boolean firstTime) {}

    public Optional<Reaction> pickDetailed(String event, Map<String, String> vars, boolean maySilence) {
        PhraseBank.Event ev = bank.event(event);
        if (ev == null) return Optional.empty();
        String group = SHARED_FIRST.getOrDefault(event, event);
        PhraseBank.Event groupEvent = Optional.ofNullable(bank.event(group)).orElse(ev);

        boolean first = false;
        List<String> pool = List.of();
        Set<String> done = new HashSet<>(memory.firstTimeDone());
        if (!done.contains(group) && hasFirstTime(groupEvent)) {
            // Su primer comentario de este evento: el grupo se bloquea aunque no haya frase usable.
            done.add(group);
            memory.saveFirstTimeDone(done);
            pool = candidates(groupEvent, firstTimeSection(groupEvent), vars);
            first = !pool.isEmpty();
        } else if (maySilence && MAY_STAY_SILENT.contains(event) && !forceStyle && rnd.nextDouble() < SILENCE_CHANCE) {
            recordStyle(List.of());
            return Optional.empty();
        }
        if (pool.isEmpty()) {
            for (String section : stage.equals("maduro 2") ? List.of("maduro 2", "maduro", "nino")
                    : stage.equals("maduro") ? List.of("maduro", "maduro 2", "nino") : List.of("nino", "maduro")) {
                pool = candidates(ev, section, vars);
                if (!pool.isEmpty()) break;
            }
        }
        if (pool.isEmpty()) return Optional.empty();

        Deque<String> last = lastPerEvent.computeIfAbsent(event, k -> new ArrayDeque<>());
        List<String> fresh = new ArrayList<>(pool);
        fresh.removeIf(last::contains);
        if (fresh.isEmpty()) {
            fresh = new ArrayList<>(pool);
            if (fresh.size() > 1) fresh.remove(last.peekLast());
        }
        // Estoico: prefiere la frase más corta (la mitad más corta). Un evento de tono "fijo" no mira el Activity Type.
        if (activityType == 0 && !"fijo".equals(ev.tone())) {
            fresh.sort(Comparator.comparingInt(String::length));
            fresh = fresh.subList(0, Math.max(1, fresh.size() / 2));
        }
        String template = fresh.get(rnd.nextInt(fresh.size()));
        last.addLast(template);
        if (last.size() > 5) last.removeFirst();
        String phrase = fill(template, vars);

        String opener = null, closer = null;
        List<String> openers = eligibleStyle(bank.style(TYPES[activityType], styleStage, ev.tone(), "antes"), phrase, true);
        List<String> closers = isUltimate() ? List.of()
                : eligibleStyle(bank.style(TYPES[activityType], styleStage, ev.tone(), "despues"), phrase, false);
        if (forceStyle) {
            int choice = rnd.nextInt(3); // 0 apertura, 1 cierre, 2 las dos
            if ((choice != 1 || closers.isEmpty()) && !openers.isEmpty()) opener = openers.get(rnd.nextInt(openers.size()));
            if ((choice != 0 || opener == null) && !closers.isEmpty()) closer = closers.get(rnd.nextInt(closers.size()));
        } else {
            double chance = STYLE_CHANCE[activityType] * (isUltimate() ? 0.5 : 1.0);
            if (!openers.isEmpty() && rnd.nextDouble() < chance) opener = openers.get(rnd.nextInt(openers.size()));
            if (!closers.isEmpty() && rnd.nextDouble() < chance) closer = closers.get(rnd.nextInt(closers.size()));
        }
        if (opener != null && closer != null && sharesWord(opener, closer)) closer = null;
        List<String> used = new ArrayList<>();
        if (opener != null) used.add(opener);
        if (closer != null) used.add(closer);
        recordStyle(used);

        String body = phrase;
        if (opener != null && opener.endsWith(",")) {
            if (!template.startsWith("{")) body = lowerFirstLetter(body); // "Oye, hecho."
        } else if (opener != null) {
            body = upperFirstLetter(body); // "Mm... La Calculadora abierta." (la frase empieza su oración)
        }
        String text = (opener == null ? "" : opener + " ") + body + (closer == null ? "" : " " + closer);
        return Optional.of(new Reaction(capitalizeSentences(text), opener, phrase, closer, first));
    }


    private void recordStyle(List<String> used) {
        lastStyle.addLast(used);
        if (lastStyle.size() > 3) lastStyle.removeFirst();
    }

    private static boolean hasFirstTime(PhraseBank.Event ev) {
        return ev.sections().keySet().stream().anyMatch(s -> s.startsWith("primera vez"));
    }

    private String firstTimeSection(PhraseBank.Event ev) {
        String specific = "primera vez " + styleStage;
        return ev.sections().containsKey(specific) ? specific : "primera vez";
    }

    /** Frases de la sección para su atributo (o "todos") que tienen todas sus variables. */
    private List<String> candidates(PhraseBank.Event ev, String section, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (PhraseBank.Phrase p : ev.sections().getOrDefault(section, List.of())) {
            boolean attrOk = p.attr().equals("todos") || p.attr().equals(attr);
            if (attrOk && hasAllVariables(p.text(), vars)) out.add(p.text());
        }
        return out;
    }

    private static boolean hasAllVariables(String template, Map<String, String> vars) {
        Matcher m = VARIABLE.matcher(template);
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null || v.isBlank()) return false;
        }
        return true;
    }

    public static String fill(String template, Map<String, String> vars) {
        Matcher m = VARIABLE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
        m.appendTail(sb);
        return sb.toString();
    }

    private List<String> eligibleStyle(List<String> pieces, String phrase, boolean opener) {
        List<String> out = new ArrayList<>();
        if (opener && INTERJECTIONS.contains(firstWord(phrase))) return out; // regla 1
        Set<String> recent = new LinkedHashSet<>();
        lastStyle.forEach(recent::addAll);                                   // regla 3
        for (String s : pieces) {
            if (recent.contains(s) || sharesWord(s, phrase)) continue;      // regla 2
            out.add(s);
        }
        return out;
    }

    // ---------------------------------------------------------------- texto

    static String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static String firstWord(String s) {
        for (String w : normalize(s).split("[^a-z-]+")) {
            if (!w.isEmpty() && !w.equals("-")) return w;
        }
        return "";
    }

    /** Palabras de 3+ letras sin las muy comunes, por su raíz (5 primeras letras: aprenderé = aprendo). */
    private static List<String> roots(String s) {
        List<String> out = new ArrayList<>();
        for (String w : normalize(s).split("[^a-z]+")) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) out.add(w.length() >= 5 ? w.substring(0, 5) : w);
        }
        return out;
    }

    static boolean sharesWord(String a, String b) {
        List<String> rb = roots(b);
        for (String w : roots(a)) if (rb.contains(w)) return true;
        return false;
    }

    private static String upperFirstLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return s.substring(0, i) + Character.toUpperCase(s.charAt(i)) + s.substring(i + 1);
            }
        }
        return s;
    }

    private static String lowerFirstLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return s.substring(0, i) + Character.toLowerCase(s.charAt(i)) + s.substring(i + 1);
            }
        }
        return s;
    }

    /**
     * Mayúscula al inicio y tras cada fin de oración (". ", "! ", "? "), sin tocar lo que sigue a unos
     * puntos suspensivos ("Snif... no entiendo" se queda así).
     */
    public static String capitalizeSentences(String s) {
        StringBuilder sb = new StringBuilder(s);
        boolean capitalizeNext = true;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (capitalizeNext && Character.isLetter(c)) {
                sb.setCharAt(i, Character.toUpperCase(c));
                capitalizeNext = false;
            } else if (capitalizeNext && Character.isDigit(c)) {
                capitalizeNext = false;
            } else if (c == '!' || c == '?') {
                capitalizeNext = nextIsSpace(sb, i);
            } else if (c == '.') {
                boolean ellipsis = (i > 0 && sb.charAt(i - 1) == '.') || (i + 1 < sb.length() && sb.charAt(i + 1) == '.');
                capitalizeNext = !ellipsis && nextIsSpace(sb, i); // "...zzz" y "Uf... ganamos" quedan en minúscula
            } else if (c == '…') {
                capitalizeNext = false;
            }
        }
        return sb.toString();
    }

    private static boolean nextIsSpace(CharSequence s, int i) {
        return i + 1 < s.length() && Character.isWhitespace(s.charAt(i + 1));
    }
}
