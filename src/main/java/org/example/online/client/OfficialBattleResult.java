package org.example.online.client;

/**
 * Resultado de una Batalla Oficial, para quien abrió la sala (el Digimon del
 * escritorio lo comenta y lo suma a su récord de Vital Values).
 *
 * @param outcome    "WIN", "LOSS" o "DRAW"
 * @param rivalStage etapa cruda de la DIM del rival (2 Child .. 5 Ultimate)
 * @param mode       Protocol.MODE_FREE u Protocol.MODE_ORIGINAL
 */
public record OfficialBattleResult(String outcome, int rivalStage, String mode) {}
