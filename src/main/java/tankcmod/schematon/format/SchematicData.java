package tankcmod.schematon.format;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.BlockAndTintGetter;
import tankcmod.schematon.compat.CompatManager;
import net.minecraft.core.HolderLookup;

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
     * Dispatches schematic loading by file extension (.nbt, .schem, .litematic).
     */
    public static SchematicData loadFromFile(File file, Level clientLevel) throws IOException {
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".nbt")) {
            return loadFromNbtFile(file, clientLevel);
        }
        if (lowerName.endsWith(".schem")) {
            return loadFromSchemFile(file, clientLevel);
        }
        if (lowerName.endsWith(".litematic")) {
            return loadFromLitematicFile(file, clientLevel);
        }
        throw new IOException("Unsupported schematic file type: " + file.getName());
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

    /**
     * Loads a Sponge schematic (.schem, v2 and v3) into a flat SchematicData grid.
     */
    public static SchematicData loadFromSchemFile(File file, Level clientLevel) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file);
        CompoundTag dataRoot = root.contains("Schematic", Tag.TAG_COMPOUND)
                ? root.getCompound("Schematic")
                : root;

        int width = dataRoot.getShort("Width") & 0xFFFF;
        int height = dataRoot.getShort("Height") & 0xFFFF;
        int length = dataRoot.getShort("Length") & 0xFFFF;
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("Invalid .schem dimensions (" + width + "x" + height + "x" + length + ").");
        }

        HolderLookup<Block> blockLookup = clientLevel.registryAccess().lookupOrThrow(Registries.BLOCK);
        Set<String> missingBlocks = new LinkedHashSet<>();

        CompoundTag paletteRoot = dataRoot.contains("Palette", Tag.TAG_COMPOUND)
                ? dataRoot.getCompound("Palette")
                : (dataRoot.contains("BlockPalette", Tag.TAG_COMPOUND) ? dataRoot.getCompound("BlockPalette") : new CompoundTag());

        Map<Integer, BlockState> indexToState = new HashMap<>();
        for (String stateString : paletteRoot.getAllKeys()) {
            int idx = paletteRoot.getInt(stateString);
            BlockState resolved = parseBlockStateString(blockLookup, stateString, missingBlocks);
            indexToState.put(idx, resolved);
        }

        byte[] blockData = dataRoot.contains("BlockData", Tag.TAG_BYTE_ARRAY)
                ? dataRoot.getByteArray("BlockData")
                : (dataRoot.contains("Blocks", Tag.TAG_COMPOUND) && dataRoot.getCompound("Blocks").contains("Data", Tag.TAG_BYTE_ARRAY)
                ? dataRoot.getCompound("Blocks").getByteArray("Data")
                : new byte[0]);

        int volume = width * height * length;
        BlockState[] blockArray = new BlockState[volume];
        Arrays.fill(blockArray, Blocks.AIR.defaultBlockState());

        int varIntIndex = 0;
        int byteOffset = 0;
        while (byteOffset < blockData.length && varIntIndex < volume) {
            int value = 0;
            int shift = 0;
            int rawByte;
            do {
                if (byteOffset >= blockData.length) {
                    throw new IOException("Truncated varint in .schem BlockData.");
                }
                if (shift > 28) {
                    throw new IOException("Varint too big in .schem BlockData.");
                }
                rawByte = blockData[byteOffset++];
                value |= (rawByte & 0x7F) << shift;
                shift += 7;
            } while ((rawByte & 0x80) != 0);

            int x = varIntIndex % width;
            int z = (varIntIndex / width) % length;
            int y = varIntIndex / (width * length);
            blockArray[getIndex(x, y, z, width, length)] = indexToState.getOrDefault(value, Blocks.AIR.defaultBlockState());
            varIntIndex++;
        }

        Map<BlockPos, CompoundTag> beTags = new HashMap<>();
        ListTag beList = dataRoot.contains("BlockEntities", Tag.TAG_LIST)
                ? dataRoot.getList("BlockEntities", Tag.TAG_COMPOUND)
                : dataRoot.getList("TileEntities", Tag.TAG_COMPOUND);

        for (int i = 0; i < beList.size(); i++) {
            CompoundTag entry = beList.getCompound(i);
            int x;
            int y;
            int z;
            if (entry.contains("Pos", Tag.TAG_INT_ARRAY)) {
                int[] posArr = entry.getIntArray("Pos");
                if (posArr.length < 3) continue;
                x = posArr[0];
                y = posArr[1];
                z = posArr[2];
            } else {
                x = entry.getInt("x");
                y = entry.getInt("y");
                z = entry.getInt("z");
            }

            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) {
                continue;
            }

            CompoundTag beData = entry.contains("Data", Tag.TAG_COMPOUND)
                    ? entry.getCompound("Data").copy()
                    : entry.copy();
            if (!beData.contains("id", Tag.TAG_STRING) && entry.contains("Id", Tag.TAG_STRING)) {
                beData.putString("id", entry.getString("Id"));
            }
            beData.putInt("x", x);
            beData.putInt("y", y);
            beData.putInt("z", z);
            beTags.put(new BlockPos(x, y, z), beData);
        }

        return new SchematicData(file.getName(), new Vec3i(width, height, length), blockArray, beTags, missingBlocks);
    }

    /**
     * Loads a Litematica (.litematic) file by normalizing all regions into a bounding SchematicData volume.
     */
    public static SchematicData loadFromLitematicFile(File file, Level clientLevel) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file);
        CompoundTag regionsTag = root.getCompound("Regions");
        if (regionsTag.isEmpty()) {
            throw new IOException("Invalid .litematic file: missing or empty 'Regions' compound.");
        }

        HolderGetter<Block> blockLookup = clientLevel.holderLookup(Registries.BLOCK);
        Set<String> missingBlocks = new LinkedHashSet<>();

        record ParsedRegion(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                            List<BlockState> palette, long[] packedStates, ListTag tileEntities) {}

        List<ParsedRegion> parsedRegions = new ArrayList<>();
        int globalMinX = Integer.MAX_VALUE, globalMinY = Integer.MAX_VALUE, globalMinZ = Integer.MAX_VALUE;
        int globalMaxX = Integer.MIN_VALUE, globalMaxY = Integer.MIN_VALUE, globalMaxZ = Integer.MIN_VALUE;

        for (String regionName : regionsTag.getAllKeys()) {
            CompoundTag regionCompound = regionsTag.getCompound(regionName);
            CompoundTag posCompound = regionCompound.getCompound("Position");
            CompoundTag sizeCompound = regionCompound.getCompound("Size");

            int rx = posCompound.getInt("x");
            int ry = posCompound.getInt("y");
            int rz = posCompound.getInt("z");

            int rawW = sizeCompound.getInt("x");
            int rawH = sizeCompound.getInt("y");
            int rawL = sizeCompound.getInt("z");

            int startX = rawW >= 0 ? rx : rx + rawW + 1;
            int startY = rawH >= 0 ? ry : ry + rawH + 1;
            int startZ = rawL >= 0 ? rz : rz + rawL + 1;

            int absW = Math.abs(rawW);
            int absH = Math.abs(rawH);
            int absL = Math.abs(rawL);
            if (absW <= 0 || absH <= 0 || absL <= 0) {
                continue;
            }

            ListTag paletteTag = regionCompound.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            List<BlockState> palette = new ArrayList<>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                CompoundTag entryTag = paletteTag.getCompound(i);
                String blockName = entryTag.getString("Name");
                BlockState state = NbtUtils.readBlockState(blockLookup, entryTag);
                if (state.isAir() && !blockName.isEmpty()
                        && !blockName.equals("minecraft:air")
                        && !blockName.equals("minecraft:cave_air")
                        && !blockName.equals("minecraft:void_air")) {
                    missingBlocks.add(blockName);
                }
                palette.add(state);
            }

            if (palette.isEmpty()) {
                palette.add(Blocks.AIR.defaultBlockState());
            }

            Tag statesTag = regionCompound.get("BlockStates");
            long[] packed = (statesTag instanceof LongArrayTag longArr) ? longArr.getAsLongArray() : new long[0];
            ListTag tileEntities = regionCompound.getList("TileEntities", Tag.TAG_COMPOUND);

            parsedRegions.add(new ParsedRegion(startX, startY, startZ, absW, absH, absL, palette, packed, tileEntities));

            globalMinX = Math.min(globalMinX, startX);
            globalMinY = Math.min(globalMinY, startY);
            globalMinZ = Math.min(globalMinZ, startZ);
            globalMaxX = Math.max(globalMaxX, startX + absW);
            globalMaxY = Math.max(globalMaxY, startY + absH);
            globalMaxZ = Math.max(globalMaxZ, startZ + absL);
        }

        if (parsedRegions.isEmpty()) {
            throw new IOException("No valid block regions found in .litematic file: " + file.getName());
        }

        int totalW = globalMaxX - globalMinX;
        int totalH = globalMaxY - globalMinY;
        int totalL = globalMaxZ - globalMinZ;

        BlockState[] blockArray = new BlockState[totalW * totalH * totalL];
        Arrays.fill(blockArray, Blocks.AIR.defaultBlockState());
        Map<BlockPos, CompoundTag> beTags = new HashMap<>();

        for (ParsedRegion region : parsedRegions) {
            int bitsPerEntry = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, region.palette().size() - 1)));
            long mask = (1L << bitsPerEntry) - 1L;
            int regionVolume = region.sizeX() * region.sizeY() * region.sizeZ();

            for (int index = 0; index < regionVolume; index++) {
                int lx = index % region.sizeX();
                int lz = (index / region.sizeX()) % region.sizeZ();
                int ly = index / (region.sizeX() * region.sizeZ());

                int paletteIndex = extractBitPackedEntry(region.packedStates(), index, bitsPerEntry, mask);
                BlockState state = (paletteIndex >= 0 && paletteIndex < region.palette().size())
                        ? region.palette().get(paletteIndex)
                        : Blocks.AIR.defaultBlockState();

                int gx = (region.minX() - globalMinX) + lx;
                int gy = (region.minY() - globalMinY) + ly;
                int gz = (region.minZ() - globalMinZ) + lz;

                if (gx >= 0 && gx < totalW && gy >= 0 && gy < totalH && gz >= 0 && gz < totalL && !state.isAir()) {
                    blockArray[getIndex(gx, gy, gz, totalW, totalL)] = state;
                }
            }

            for (int i = 0; i < region.tileEntities().size(); i++) {
                CompoundTag teTag = region.tileEntities().getCompound(i);
                int lx;
                int ly;
                int lz;
                if (teTag.contains("x", Tag.TAG_INT) && teTag.contains("y", Tag.TAG_INT) && teTag.contains("z", Tag.TAG_INT)) {
                    lx = teTag.getInt("x");
                    ly = teTag.getInt("y");
                    lz = teTag.getInt("z");
                } else if (teTag.contains("pos", Tag.TAG_INT_ARRAY)) {
                    int[] pos = teTag.getIntArray("pos");
                    if (pos.length < 3) continue;
                    lx = pos[0];
                    ly = pos[1];
                    lz = pos[2];
                } else {
                    continue;
                }

                int gx = (region.minX() - globalMinX) + lx;
                int gy = (region.minY() - globalMinY) + ly;
                int gz = (region.minZ() - globalMinZ) + lz;
                if (gx >= 0 && gx < totalW && gy >= 0 && gy < totalH && gz >= 0 && gz < totalL) {
                    CompoundTag copied = teTag.copy();
                    copied.putInt("x", gx);
                    copied.putInt("y", gy);
                    copied.putInt("z", gz);
                    beTags.put(new BlockPos(gx, gy, gz), copied);
                }
            }
        }

        return new SchematicData(file.getName(), new Vec3i(totalW, totalH, totalL), blockArray, beTags, missingBlocks);
    }

    private static int extractBitPackedEntry(long[] packed, int index, int bitsPerEntry, long mask) {
        long bitIndex = (long) index * bitsPerEntry;
        int startLong = (int) (bitIndex >>> 6);
        int startOffset = (int) (bitIndex & 63L);
        if (startLong >= packed.length) {
            return 0;
        }
        long value = packed[startLong] >>> startOffset;
        int endOffset = startOffset + bitsPerEntry;
        if (endOffset > 64 && startLong + 1 < packed.length) {
            value |= packed[startLong + 1] << (64 - startOffset);
        }
        return (int) (value & mask);
    }

    private static BlockState parseBlockStateString(HolderLookup<Block> blockLookup, String spec, Set<String> missingBlocks) {
        try {
            BlockStateParser.BlockResult parsed = BlockStateParser.parseForBlock(blockLookup, new StringReader(spec), false);
            BlockState state = parsed.blockState();
            if (state.isAir() && !spec.startsWith("minecraft:air")
                    && !spec.startsWith("minecraft:cave_air")
                    && !spec.startsWith("minecraft:void_air")) {
                String blockId = spec.contains("[") ? spec.substring(0, spec.indexOf('[')) : spec;
                missingBlocks.add(blockId);
            }
            return state;
        } catch (CommandSyntaxException ex) {
            String baseName = spec.contains("[") ? spec.substring(0, spec.indexOf('[')) : spec;
            CompoundTag fallbackTag = new CompoundTag();
            fallbackTag.putString("Name", baseName);
            BlockState baseState = NbtUtils.readBlockState(blockLookup, fallbackTag);
            if (baseState.isAir() && !baseName.equals("minecraft:air")) {
                missingBlocks.add(baseName);
            }
            if (!baseState.isAir() && spec.contains("[") && spec.endsWith("]")) {
                String propsPart = spec.substring(spec.indexOf('[') + 1, spec.length() - 1);
                for (String pair : propsPart.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String propName = pair.substring(0, eq).trim();
                        String propVal = pair.substring(eq + 1).trim();
                        baseState = applyPropertyValue(baseState, propName, propVal);
                    }
                }
            }
            return baseState;
        }
    }

    private static <T extends Comparable<T>> BlockState applyPropertyValue(BlockState state, String propName, String propVal) {
        for (Property<?> rawProp : state.getProperties()) {
            if (rawProp.getName().equals(propName)) {
                @SuppressWarnings("unchecked")
                Property<T> prop = (Property<T>) rawProp;
                Optional<T> parsedVal = prop.getValue(propVal);
                if (parsedVal.isPresent()) {
                    return state.setValue(prop, parsedVal.get());
                }
            }
        }
        return state;
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