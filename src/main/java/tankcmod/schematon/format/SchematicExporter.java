package tankcmod.schematon.format;

import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import tankcmod.schematon.compat.CompatManager;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SchematicExporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    @FunctionalInterface
    public interface ExportProgressCallback {
        void onProgress(float progress, String status);
    }

    public record ExportResult(
            boolean success,
            @Nullable File outputFile,
            Vec3i size,
            int paletteSize,
            int nonAirBlocks,
            int blockEntitiesSaved,
            int entitiesSaved,
            boolean serverAuthoritative,
            String message
    ) {}

    /**
     * Exports a region [pos1..pos2] from the given Level (ServerLevel or client Level)
     * into a compressed Structure .nbt file inside outputDir.
     */
    /**
     * Backwards-compatible overload defaulting to ignoreAir = true, ignoreContents = false.
     */
    public static ExportResult exportToNbtFile(
            Level level,
            BlockPos pos1,
            BlockPos pos2,
            File outputDir,
            String fileName,
            boolean includeEntities
    ) {
        return exportToNbtFile(level, pos1, pos2, outputDir, fileName, includeEntities, true, false);
    }

    /**
     * Backwards-compatible overload without progress callback.
     */
    public static ExportResult exportToNbtFile(
            Level level,
            BlockPos pos1,
            BlockPos pos2,
            File outputDir,
            String fileName,
            boolean includeEntities,
            boolean ignoreAir,
            boolean ignoreContents
    ) {
        return exportToNbtFile(level, pos1, pos2, outputDir, fileName, includeEntities, ignoreAir, ignoreContents, null);
    }

    /**
     * Exports a region [pos1..pos2] from the given Level (ServerLevel or client Level)
     * into a compressed Structure .nbt file inside outputDir.
     */
    public static ExportResult exportToNbtFile(
            Level level,
            BlockPos pos1,
            BlockPos pos2,
            File outputDir,
            String fileName,
            boolean includeEntities,
            boolean ignoreAir,
            boolean ignoreContents, @Nullable ExportProgressCallback progressCallback
    ) {
        if (level == null) {
            return fail(fileName, "World level is null.");
        }
        if (pos1 == null || pos2 == null) {
            return fail(fileName, "Both Pos 1 and Pos 2 must be set before exporting.");
        }

        BlockPos minPos = new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
        BlockPos maxPos = new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        );

        int width = maxPos.getX() - minPos.getX() + 1;
        int height = maxPos.getY() - minPos.getY() + 1;
        int length = maxPos.getZ() - minPos.getZ() + 1;
        Vec3i size = new Vec3i(width, height, length);

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            return fail(fileName, "Failed to create output directory: " + outputDir.getAbsolutePath());
        }

        String safeFileName = sanitizeNbtFileName(fileName);
        File outFile = new File(outputDir, safeFileName);

        boolean isServerAuthoritative = !level.isClientSide;
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndexMap = new HashMap<>();
        ListTag blocksTag = new ListTag();

        // Ensure index 0 is minecraft:air for standard Structure NBT compatibility
        BlockState airState = Blocks.AIR.defaultBlockState();
        palette.add(airState);
        paletteIndexMap.put(airState, 0);

        int nonAirBlocks = 0;
        int blockEntitiesSaved = 0;
        int unloadedSkipped = 0;

        BlockPos.MutableBlockPos worldCursor = new BlockPos.MutableBlockPos();

        if (progressCallback != null) {
            progressCallback.onProgress(0.02f, "Scanning layer 0 / " + height + "...");
        }

        for (int y = 0; y < height; y++) {
            int wy = minPos.getY() + y;
            for (int z = 0; z < length; z++) {
                int wz = minPos.getZ() + z;
                for (int x = 0; x < width; x++) {
                    int wx = minPos.getX() + x;
                    worldCursor.set(wx, wy, wz);

                    if (!level.isLoaded(worldCursor)) {
                        unloadedSkipped++;
                        continue;
                    }

                    BlockState state = level.getBlockState(worldCursor);
                    if (state.isAir() && ignoreAir) {
                        continue;
                    }

                    int stateId = paletteIndexMap.computeIfAbsent(state, s -> {
                        int idx = palette.size();
                        palette.add(s);
                        return idx;
                    });

                    CompoundTag blockEntry = new CompoundTag();
                    ListTag posList = new ListTag();
                    posList.add(IntTag.valueOf(x));
                    posList.add(IntTag.valueOf(y));
                    posList.add(IntTag.valueOf(z));
                    blockEntry.put("pos", posList);
                    blockEntry.putInt("state", stateId);

                    BlockEntity be = level.getBlockEntity(worldCursor);
                    if (be != null) {
                        CompoundTag rawBeTag = be.saveWithFullMetadata();
                        BlockPos immutableWorldPos = worldCursor.immutable();
                        CompoundTag preparedBeTag = CompatManager.prepareSaveTag(be, rawBeTag, immutableWorldPos, level);
                        if (be instanceof RandomizableContainerBlockEntity || rawBeTag.contains("Items") || (preparedBeTag != null && preparedBeTag.contains("Items"))) {
                            LOGGER.info("[Schematon Export] Server BE at {} ({}) | ignoreContents={} | rawItems={} | preparedItems={}",
                                    immutableWorldPos,
                                    be.getType(),
                                    ignoreContents,
                                    rawBeTag.contains("Items") ? rawBeTag.get("Items") : "<none>",
                                    (preparedBeTag != null && preparedBeTag.contains("Items")) ? preparedBeTag.get("Items") : "<none>");
                        }
                        if (preparedBeTag != null && !preparedBeTag.isEmpty()) {
                            CompoundTag beCopy = preparedBeTag.copy();
                            if (ignoreContents) {
                                stripItemContents(beCopy);
                            }
                            beCopy.putInt("x", x);
                            beCopy.putInt("y", y);
                            beCopy.putInt("z", z);
                            blockEntry.put("nbt", beCopy);
                            blockEntitiesSaved++;
                        }
                    }

                    blocksTag.add(blockEntry);
                    if (!state.isAir()) {
                        nonAirBlocks++;
                    }
                }
            }

            if (progressCallback != null) {
                float scanFrac = (float) (y + 1) / (float) height;
                progressCallback.onProgress(0.05f + (scanFrac * 0.80f), "Scanning layer " + (y + 1) + " / " + height + "...");
            }
        }

        if (progressCallback != null) {
            progressCallback.onProgress(0.88f, "Building palette & NBT...");
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(width));
        sizeTag.add(IntTag.valueOf(height));
        sizeTag.add(IntTag.valueOf(length));
        root.put("size", sizeTag);
        root.put("palette", paletteTag);
        root.put("blocks", blocksTag);

        int entitiesSaved = 0;
        if (includeEntities) {
            if (progressCallback != null) {
                progressCallback.onProgress(0.92f, "Capturing entities...");
            }
            ListTag entitiesTag = captureEntities(level, minPos, maxPos);
            entitiesSaved = entitiesTag.size();
            root.put("entities", entitiesTag);
        }
        if (progressCallback != null) {
        progressCallback.onProgress(0.96f, "Writing compressed .nbt...");
        }
        try {
            NbtIo.writeCompressed(root, outFile);
        } catch (IOException e) {
            LOGGER.error("Failed writing schematic NBT file '{}'", outFile.getAbsolutePath(), e);
            return fail(safeFileName, "I/O error writing .nbt: " + e.getMessage());
        }

        String sourceLabel = isServerAuthoritative ? "Server" : "Client";
        String summary = "Exported " + safeFileName + " [" + sourceLabel + "] ("
                + width + "x" + height + "x" + length + ", "
                + nonAirBlocks + " blocks, "
                + blockEntitiesSaved + " block entities"
                + (includeEntities ? ", " + entitiesSaved + " entities" : "")
                + (!ignoreAir ? ", +air" : "")
                + (ignoreContents ? ", no-contents" : "")
                + (unloadedSkipped > 0 ? ", " + unloadedSkipped + " unloaded skipped" : "")
                + ")";

        return new ExportResult(
                true,
                outFile,
                size,
                palette.size(),
                nonAirBlocks,
                blockEntitiesSaved,
                entitiesSaved,
                isServerAuthoritative,
                summary
        );
    }

    private static void stripItemContents(CompoundTag tag) {
        tag.remove("Items");
        tag.remove("Inventory");
        tag.remove("ItemStack");
        tag.remove("Item");

        Iterator<String> keyIterator = tag.getAllKeys().iterator();
        while (keyIterator.hasNext()) {
            String key = keyIterator.next();
            Tag entry = tag.get(key);
            if (entry instanceof CompoundTag subCompound) {
                if (looksLikeItemStack(subCompound)) {
                    keyIterator.remove();
                } else {
                    stripItemContents(subCompound);
                }
            } else if (entry instanceof ListTag listTag) {
                stripItemList(listTag);
            }
        }
    }

    private static void stripItemList(ListTag listTag) {
        for (int i = listTag.size() - 1; i >= 0; i--) {
            Tag element = listTag.get(i);
            if (element instanceof CompoundTag compound) {
                if (looksLikeItemStack(compound)) {
                    listTag.remove(i);
                } else {
                    stripItemContents(compound);
                }
            } else if (element instanceof ListTag nestedList) {
                stripItemList(nestedList);
            }
        }
    }

    private static boolean looksLikeItemStack(CompoundTag compound) {
        boolean hasId = compound.contains("id", Tag.TAG_STRING);
        boolean hasCount = compound.contains("Count", Tag.TAG_ANY_NUMERIC)
                || compound.contains("count", Tag.TAG_ANY_NUMERIC);
        return hasId && hasCount;
    }

    private static ListTag captureEntities(Level level, BlockPos minPos, BlockPos maxPos) {
        ListTag entitiesTag = new ListTag();
        AABB bounds = new AABB(
                minPos.getX(), minPos.getY(), minPos.getZ(),
                maxPos.getX() + 1.0, maxPos.getY() + 1.0, maxPos.getZ() + 1.0
        );

        List<Entity> entities = level.getEntities((Entity) null, bounds, entity -> !(entity instanceof Player));
        for (Entity entity : entities) {
            CompoundTag nbt = new CompoundTag();
            if (!entity.saveAsPassenger(nbt)) {
                continue;
            }

            // Strip UUID so pasting spawns a fresh entity identity without UUID collisions
            nbt.remove("UUID");

            Vec3 relPos = entity.position().subtract(minPos.getX(), minPos.getY(), minPos.getZ());
            ListTag localPosTag = new ListTag();
            localPosTag.add(DoubleTag.valueOf(relPos.x));
            localPosTag.add(DoubleTag.valueOf(relPos.y));
            localPosTag.add(DoubleTag.valueOf(relPos.z));

            CompoundTag entityEntry = new CompoundTag();
            entityEntry.put("pos", localPosTag);
            entityEntry.put("nbt", nbt);
            entitiesTag.add(entityEntry);
        }

        return entitiesTag;
    }

    public static String sanitizeNbtFileName(@Nullable String rawName) {
        String base = (rawName == null) ? "" : rawName.trim();
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isEmpty() || sanitized.equals(".nbt")) {
            sanitized = "schematic_" + System.currentTimeMillis();
        }
        if (!sanitized.toLowerCase().endsWith(".nbt")) {
            sanitized += ".nbt";
        }
        return sanitized;
    }

    private static ExportResult fail(String fileName, String reason) {
        return new ExportResult(
                false,
                null,
                Vec3i.ZERO,
                0,
                0,
                0,
                0,
                false,
                "Export failed (" + fileName + "): " + reason
        );
    }
}