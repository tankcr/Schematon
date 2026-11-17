package tankcmod.schematon.format;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.BlockAndTintGetter;
import tankcmod.schematon.compat.CompatManager;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SchematicData {
    private final String name;
    private final Vec3i size;
    private final BlockState[] blocks;
    private final Map<BlockPos, CompoundTag> blockEntityTags;
    private final Map<BlockPos, BlockEntity> cachedBlockEntities;
    private final Set<String> missingModBlocks;

    public SchematicData(String name, Vec3i size, BlockState[] blocks,
                         Map<BlockPos, CompoundTag> blockEntityTags,
                         Set<String> missingModBlocks) {
        this.name = name;
        this.size = size;
        this.blocks = blocks;
        this.blockEntityTags = Collections.unmodifiableMap(blockEntityTags);
        this.cachedBlockEntities = new HashMap<>();
        this.missingModBlocks = Collections.unmodifiableSet(missingModBlocks);
    }

    /**
     * Loads a 1.20.1 Structure .nbt file (including modded blocks & BlockEntity NBT).
     */
    public static SchematicData loadFromNbtFile(File file, Level clientLevel) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file);

        // 1. Read Dimensions: [width (X), height (Y), length (Z)]
        ListTag sizeTag = root.getList("size", Tag.TAG_INT);
        if (sizeTag.size() != 3) {
            throw new IOException("Invalid schematic NBT: missing or malformed 'size' tag.");
        }
        int width = sizeTag.getInt(0);
        int height = sizeTag.getInt(1);
        int length = sizeTag.getInt(2);
        Vec3i size = new Vec3i(width, height, length);

        // 2. Resolve Palette via the active Level's Block Registry Lookup
        HolderGetter<Block> blockLookup = clientLevel.holderLookup(Registries.BLOCK);
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        Set<String> missingBlocks = new LinkedHashSet<>();

        for (int i = 0; i < paletteTag.size(); i++) {
            CompoundTag entryTag = paletteTag.getCompound(i);
            String blockName = entryTag.getString("Name");
            BlockState resolvedState = NbtUtils.readBlockState(blockLookup, entryTag);

            if (resolvedState.isAir() && !blockName.equals("minecraft:air")
                    && !blockName.equals("minecraft:cave_air") && !blockName.equals("minecraft:void_air")) {
                missingBlocks.add(blockName);
            }
            palette.add(resolvedState);
        }

        // 3. Populate Flat 1D BlockState Array & Modded BlockEntity NBT Map
        int volume = width * height * length;
        BlockState[] blockArray = new BlockState[volume];
        Arrays.fill(blockArray, Blocks.AIR.defaultBlockState());

        Map<BlockPos, CompoundTag> beTags = new HashMap<>();
        ListTag blocksTag = root.getList("blocks", Tag.TAG_COMPOUND);

        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag blockTag = blocksTag.getCompound(i);
            ListTag posTag = blockTag.getList("pos", Tag.TAG_INT);
            int x = posTag.getInt(0);
            int y = posTag.getInt(1);
            int z = posTag.getInt(2);

            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) {
                continue;
            }

            int stateIndex = blockTag.getInt("state");
            if (stateIndex >= 0 && stateIndex < palette.size()) {
                BlockState state = palette.get(stateIndex);
                blockArray[getIndex(x, y, z, width, length)] = state;

                if (blockTag.contains("nbt", Tag.TAG_COMPOUND)) {
                    BlockPos localPos = new BlockPos(x, y, z);
                    CompoundTag beData = blockTag.getCompound("nbt").copy();
                    beData.putInt("x", x);
                    beData.putInt("y", y);
                    beData.putInt("z", z);
                    beTags.put(localPos, beData);
                }
            }
        }

        return new SchematicData(file.getName(), size, blockArray, beTags, missingBlocks);
    }

    public BlockState getBlockState(int x, int y, int z) {
        if (!isInside(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        return blocks[getIndex(x, y, z, size.getX(), size.getZ())];
    }

    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Nullable
    public BlockEntity getOrCreateBlockEntity(BlockPos pos, Level clientLevel, BlockAndTintGetter schematicLevel) {
        if (cachedBlockEntities.containsKey(pos)) {
            return cachedBlockEntities.get(pos);
        }

        BlockState state = getBlockState(pos);
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            cachedBlockEntities.put(pos, null);
            return null;
        }

        BlockEntity be = entityBlock.newBlockEntity(pos, state);
        if (be != null) {
            CompoundTag tag = blockEntityTags.get(pos);
            if (tag != null) {
                // 1. Let handlers fix up missing NBT (like AE2 missing connections)
                tag = CompatManager.prepareTag(be, tag, pos, schematicLevel);

                // 2. LOAD NBT BEFORE ATTACHING CLIENT LEVEL!
                // This bypasses isClientSide() guards in mods so they actually initialize their parts.
                be.load(tag);
            }

            // 3. Attach the client level now that the raw data is safely inside
            be.setLevel(clientLevel);

            // 4. Trigger post-load client visual updates
            if (tag != null) {
                CompatManager.onBlockEntityLoad(be, tag, pos, schematicLevel);
            }
        }

        cachedBlockEntities.put(pos, be);
        return be;
    }

    public boolean isInside(int x, int y, int z) {
        return x >= 0 && x < size.getX()
                && y >= 0 && y < size.getY()
                && z >= 0 && z < size.getZ();
    }

    private static int getIndex(int x, int y, int z, int width, int length) {
        return x + (z * width) + (y * width * length);
    }

    public String getName() { return name; }
    public Vec3i getSize() { return size; }
    public Set<String> getMissingModBlocks() { return missingModBlocks; }
    public Map<BlockPos, CompoundTag> getBlockEntityTags() { return blockEntityTags; }
}
