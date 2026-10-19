package org.example.dim;

import com.github.cfogrady.vb.dim.sprite.SpriteData;

import java.nio.file.Path;
import java.util.List;

/**
 * Resultado de VsDimReader. Los stats son los BASE del Digimon (lo que dice
 * la tabla de la DIM de origen); el bono por Power Trophies del mod Digimon
 * Link se calcula aparte con PowerTrophyBonus, nunca se guarda en la VS DIM.
 */
public record VsDimData(
        Path sourcePath,
        int slot,
        List<StatsRow> stats,
        List<SpriteData.Sprite> sprites,
        int vitalValues,
        int powerTrophies,
        /** Offset 0x40000 [2]: vale 100 en los 3 archivos analizados. Significado sin confirmar. */
        int unknownStateValue
) {

    /** Una fila de la tabla de stats (0x30000). 0xFFFF = "no aplica" (ver NONE_VALUE en BattleEngine). */
    public record StatsRow(int stage, boolean unlockRequired, int attribute, int activityType,
                           int smallAttackId, int bigAttackId, int dpStars, int dp, int hp, int ap,
                           int firstPoolBattleChance, int secondPoolBattleChance) {}

    public StatsRow character() { return stats.get(slot); }
}
