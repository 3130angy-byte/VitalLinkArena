package org.example.sound;

/**
 * Efectos de sonido del Vital Bracelet (archivos 1.mp3 ... 27.mp3 de la
 * carpeta DigimonProjectData\sounds; qué es cada número lo identificó el
 * usuario). El número de cada momento se puede cambiar en
 * config\sonidos.properties sin recompilar (varios son "no estoy seguro").
 */
public enum Sfx {
    CONFIRMAR("confirmar", 1, "Elegir una opción (menú, botones)"),
    SELECCIONAR("seleccionar", 2, "Abrir el menú; tocar un número del COMBO de la ARENA"),
    CANCELAR("cancelar", 4, "Volver / cerrar; número equivocado en la ARENA"),
    RESPALDO("respaldo", 6, "Se guardó un Digimon (copia de batalla, cápsula o devolución al VB)"),
    EVOLUCION_ESPERA("evolucionEspera", 8, "Esperando para evolucionar (aviso de Jogress)"),
    EVOLUCION("evolucion", 9, "\"EVOLUTION\": aparece la nueva forma"),
    GO("go", 10, "GO! al empezar un entrenamiento o el COMBO de la ARENA"),
    LESION("lesion", 11, "Se lesionó"),
    ECLOSION("eclosion", 12, "Fin de la eclosión del huevo"),
    MUERTE("muerte", 13, "Muerte"),
    AVISO("aviso", 14, "WARNING: alguien te reta en la sala online"),
    INICIO_BATALLA("inicioBatalla", 16, "Empieza la batalla"),
    LISTO("listo", 17, "READY (antes del COMBO de la ARENA)"),
    ATAQUE("ataque", 19, "Ataque normal"),
    ATAQUE_GRANDE("ataqueGrande", 20, "Ataque grande"),
    GOLPE("golpe", 21, "El ataque acierta"),
    ESQUIVA("esquiva", 22, "El ataque falla (esquiva)"),
    CUTIN_GRANDE("cutinGrande", 23, "Antes de un ataque grande o W-ATTACK"),
    VICTORIA("victoria", 24, "Victoria; entrenamiento bien hecho"),
    DERROTA("derrota", 25, "Derrota; entrenamiento fallido"),
    EVOLUCION_FIN("evolucionFin", 26, "Final de la evolución"),
    EVOLUCIONANDO("evolucionando", 27, "Pitidos durante la animación de evolución"),
    HUEVO("huevo", 1, "El huevo se mueve antes de eclosionar"),
    LLAMADA("llamada", 5, "Te llama: hambre o suciedad (no se sabe qué es el 5 en el VB)"),
    ERROR_CUIDADO("errorCuidado", 25, "Error de cuidado: no lo atendiste a tiempo");

    /** Clave en sonidos.properties. */
    public final String key;
    /** Archivo por defecto (N.mp3). */
    public final int defaultFile;
    public final String description;

    Sfx(String key, int defaultFile, String description) {
        this.key = key;
        this.defaultFile = defaultFile;
        this.description = description;
    }
}
