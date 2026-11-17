package tankcmod.schematon.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

public class CompatManager {
    private static final List<ISchematicCompat> HANDLERS = new ArrayList<>();

    static {
        HANDLERS.add(new AE2Compat());
    }

    public static CompoundTag prepareTag(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        if (be == null) return tag;
        ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
        if (id != null) {
            String namespace = id.getNamespace();
            for (ISchematicCompat handler : HANDLERS) {
                if (handler.matches(namespace)) {
                    return handler.prepareTag(be, tag, localPos, schematicLevel);
                }
            }
        }
        return tag;
    }

    public static void onBlockEntityLoad(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        if (be == null) return;
        ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
        if (id != null) {
            String namespace = id.getNamespace();
            for (ISchematicCompat handler : HANDLERS) {
                if (handler.matches(namespace)) {
                    handler.onBlockEntityLoad(be, tag, localPos, schematicLevel);
                    return;
                }
            }
        }
    }

    public static ModelData getModelData(BlockEntity be) {
        if (be == null) return ModelData.EMPTY;
        ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
        if (id != null) {
            String namespace = id.getNamespace();
            for (ISchematicCompat handler : HANDLERS) {
                if (handler.matches(namespace)) {
                    return handler.getModelData(be);
                }
            }
        }
        return be.getModelData();
    }
}