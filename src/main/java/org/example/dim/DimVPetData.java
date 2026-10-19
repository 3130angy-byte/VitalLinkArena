package org.example.dim;

import com.github.cfogrady.vb.dim.adventure.DimAdventures;
import com.github.cfogrady.vb.dim.card.DimCard;
import com.github.cfogrady.vb.dim.card.DimReader;
import com.github.cfogrady.vb.dim.character.DimStats;
import com.github.cfogrady.vb.dim.fusion.DimFusions;
import com.github.cfogrady.vb.dim.fusion.DimSpecificFusions;
import com.github.cfogrady.vb.dim.header.DimHeader;
import com.github.cfogrady.vb.dim.sprite.SpriteData;
import com.github.cfogrady.vb.dim.transformation.DimEvolutionRequirements;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DimVPetData {

    /** dimId ficticio para tarjetas armadas desde una VS DIM: nunca coincide con una Jogress específica real. */
    public static final int VS_DIM_SYNTHETIC_ID = 0xFFFF;

    private final Path sourcePath;
    private final DimCard card;
    private final int activeSlot;
    /** null en una DIM normal. En una VS DIM: los datos leídos (solo trae sprites de su slot). */
    private final VsDimData vsData;

    private DimVPetData(
            Path sourcePath,
            DimCard card,
            int activeSlot
    ) {
        this(sourcePath, card, activeSlot, null);
    }

    private DimVPetData(Path sourcePath, DimCard card, int activeSlot, VsDimData vsData) {
        this.sourcePath = sourcePath;
        this.card = card;
        this.activeSlot = activeSlot;
        this.vsData = vsData;
    }

    /**
     * Arma un DimCard sintético a partir de una VS DIM, para que el resto
     * del programa la use igual que una DIM normal: tabla de stats real de
     * la DIM de origen, y SIN evoluciones, Adventure ni Jogress (forma fija,
     * decisión del usuario). Los sprites NO van dentro del DimCard: solo
     * existen los del slot transferido y los sirve getSpritesForSlot.
     */
    public static DimVPetData fromVsDim(VsDimData vs) {
        List<DimStats.DimStatBlock> entries = new ArrayList<>();
        for (VsDimData.StatsRow row : vs.stats()) {
            entries.add(DimStats.DimStatBlock.builder()
                    .stage(row.stage()).unlockRequired(row.unlockRequired())
                    .attribute(row.attribute()).type(row.activityType())
                    .smallAttackId(row.smallAttackId()).bigAttackId(row.bigAttackId())
                    .dpStars(row.dpStars()).dp(row.dp()).hp(row.hp()).ap(row.ap())
                    .firstPoolBattleChance(row.firstPoolBattleChance())
                    .secondPoolBattleChance(row.secondPoolBattleChance())
                    .build());
        }

        DimCard card = DimCard.builder()
                .header(DimHeader.builder().text("VS DIM").dimId(VS_DIM_SYNTHETIC_ID).build())
                .characterStats(DimStats.builder().characterEntries(entries).build())
                .transformationRequirements(DimEvolutionRequirements.builder().transformationEntries(List.of()).build())
                .adventureLevels(DimAdventures.builder().levels(List.of()).build())
                .attributeFusions(DimFusions.builder().entries(List.of()).build())
                .specificFusions(DimSpecificFusions.builder().entries(List.of()).build())
                .spriteData(SpriteData.builder().sprites(List.of()).build())
                .build();

        return new DimVPetData(vs.sourcePath().toAbsolutePath(), card, vs.slot(), vs);
    }

    public boolean isVsDim() { return vsData != null; }

    /** null si no es VS DIM. */
    public VsDimData getVsData() { return vsData; }

    /** En una VS DIM solo el slot transferido tiene sprites; en una DIM normal, todos. */
    public boolean hasSpritesForSlot(int slot) {
        return vsData == null || slot == vsData.slot();
    }

    public static DimVPetData load(
            Path path,
            int activeSlot
    ) throws Exception {

        if (path == null) {
            throw new IllegalArgumentException(
                    "La ruta de la DIM no puede ser null."
            );
        }

        if (!Files.exists(path)) {
            throw new IllegalArgumentException(
                    "No existe la DIM:\n"
                            + path.toAbsolutePath()
            );
        }

        if (activeSlot < 0) {
            throw new IllegalArgumentException(
                    "El slot activo no puede ser negativo."
            );
        }

        // VB-DIM-Reader no puede abrir VS DIMs (ver VsDimReader). En ese caso
        // el slot lo decide el archivo, no quien llama.
        if (VsDimReader.isVsDimFile(path)) {
            return fromVsDim(VsDimReader.read(path));
        }

        DimReader reader =
                new DimReader();

        DimCard dimCard;

        try (InputStream inputStream =
                     Files.newInputStream(path)) {

            /*
             * Usamos el lector real de VB-DIM-Reader.
             */
            dimCard =
                    reader.readDimData(
                            inputStream,
                            false
                    );
        }

        int characterCount =
                dimCard
                        .getCharacterStats()
                        .getCharacterEntries()
                        .size();

        if (activeSlot >= characterCount) {

            throw new IllegalArgumentException(
                    "El slot "
                            + activeSlot
                            + " no existe en esta DIM.\n"
                            + "Slots disponibles: "
                            + characterCount
            );
        }

        return new DimVPetData(
                path.toAbsolutePath(),
                dimCard,
                activeSlot
        );
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public DimCard getCard() {
        return card;
    }

    public int getActiveSlot() {
        return activeSlot;
    }

    public int getActiveStage() {

        return card
                .getCharacterStats()
                .getCharacterEntries()
                .get(activeSlot)
                .getStage();
    }

    /**
     * Igual que getActiveStage(), pero para cualquier slot — necesario
     * para construir el DimSpriteSet del personaje al que se evoluciona,
     * que no es el activeSlot con el que se cargó la DIM.
     */
    public int getStageForSlot(
            int slot
    ) {

        int characterCount =
                card
                        .getCharacterStats()
                        .getCharacterEntries()
                        .size();

        if (slot < 0 || slot >= characterCount) {

            throw new IllegalArgumentException(
                    "Slot inválido: " + slot
            );
        }

        return card
                .getCharacterStats()
                .getCharacterEntries()
                .get(slot)
                .getStage();
    }

    /**
     * Obtiene los sprites correspondientes a un slot.
     *
     * La estructura de DIM comienza con:
     *
     * 0  Logo
     * 1  Background
     * 2-9  Huevos
     *
     * Los personajes comienzan en el sprite 10.
     *
     * Stage 0 = 6 sprites
     * Stage 1 = 7 sprites
     * Stage 2+ = 14 sprites
     */
    public List<SpriteData.Sprite> getSpritesForSlot(
            int slot
    ) {

        if (vsData != null) {
            if (slot != vsData.slot()) {
                throw new IllegalStateException(
                        "VS DIM: solo trae los sprites del slot " + vsData.slot()
                                + ", se pidió el slot " + slot);
            }
            return Collections.unmodifiableList(vsData.sprites());
        }

        List<SpriteData.Sprite> allSprites =
                card
                        .getSpriteData()
                        .getSprites();

        int characterCount =
                card
                        .getCharacterStats()
                        .getCharacterEntries()
                        .size();

        if (slot < 0 || slot >= characterCount) {

            throw new IllegalArgumentException(
                    "Slot inválido: " + slot
            );
        }

        int startingSprite = 10;

        /*
         * Saltamos todos los personajes anteriores.
         */
        for (int i = 0; i < slot; i++) {

            int stage =
                    card
                            .getCharacterStats()
                            .getCharacterEntries()
                            .get(i)
                            .getStage();

            startingSprite +=
                    numberOfSpritesForStage(stage);
        }

        int stage =
                card
                        .getCharacterStats()
                        .getCharacterEntries()
                        .get(slot)
                        .getStage();

        int spriteCount =
                numberOfSpritesForStage(stage);

        int end =
                startingSprite + spriteCount;

        if (startingSprite < 0
                || end > allSprites.size()) {

            throw new IllegalStateException(
                    "La DIM no contiene suficientes sprites "
                            + "para el slot " + slot
            );
        }

        return Collections.unmodifiableList(
                new ArrayList<>(
                        allSprites.subList(
                                startingSprite,
                                end
                        )
                )
        );
    }


    public static int numberOfSpritesForStage(
            int stage
    ) {

        if (stage == 0) {
            return 6;
        }

        if (stage == 1) {
            return 7;
        }

        return 14;
    }
}