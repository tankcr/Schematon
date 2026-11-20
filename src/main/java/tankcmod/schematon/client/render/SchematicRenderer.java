package tankcmod.schematon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import tankcmod.schematon.format.SchematicData;
import tankcmod.schematon.world.SchematicPlacement;
import tankcmod.schematon.world.VirtualSchematicLevel;

public class SchematicRenderer {
    private static VertexBuffer cachedBuffer = null;
    private static boolean dirty = true;

    private static final float GHOST_ALPHA = 0.65f;

    public static void markDirty() {
        dirty = true;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        VirtualSchematicLevel virtualLevel = SchematicPlacement.getActiveSchematic();
        if (virtualLevel == null) {
            releaseBuffer();
            return;
        }

        // ADDED: Skip rendering if the schematic is hidden
        if (SchematicPlacement.isHidden()) {
            return;
        }

        if (dirty || cachedBuffer == null) {
            rebuildBuffer(virtualLevel);
            dirty = false;
        }

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        BlockPos origin = SchematicPlacement.getOrigin();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        poseStack.translate(
                origin.getX() - camPos.x,
                origin.getY() - camPos.y,
                origin.getZ() - camPos.z
        );

        // 1. Render cached translucent ghost blocks
        if (cachedBuffer != null && !cachedBuffer.isInvalid()) {
            RenderType translucent = RenderType.translucent();
            translucent.setupRenderState();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            // Keep depth TEST on (so terrain occludes the hologram), but disable depth WRITE
            // so unsorted translucent quads never occlude other ghost faces behind them
            RenderSystem.depthMask(false);
            // Pull ghost quads a hair toward the camera to prevent Z-fighting on coplanar faces
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(-1.0f, -1.0f);
            RenderSystem.setShaderColor(0.85f, 0.92f, 1.0f, GHOST_ALPHA);

            Matrix4f modelView = poseStack.last().pose();
            Matrix4f projection = event.getProjectionMatrix();

            cachedBuffer.bind();
            cachedBuffer.drawWithShader(modelView, projection, GameRenderer.getRendertypeTranslucentShader());
            VertexBuffer.unbind();

            // Always restore GL state before clearing RenderState
            RenderSystem.polygonOffset(0.0f, 0.0f);
            RenderSystem.disablePolygonOffset();
            RenderSystem.depthMask(true);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.disableBlend();
            translucent.clearRenderState();
        }

        // 2. Render crisp cyan wireframe using ROTATED dimensions
        renderBoundingBox(poseStack, virtualLevel.getRotatedSize());

        // 3. Render Red/Green Placement Validation Highlights
        renderValidationOverlays(poseStack, virtualLevel, origin);

        poseStack.popPose();
    }

    private static void rebuildBuffer(VirtualSchematicLevel virtualLevel) {
        releaseBuffer();

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        Vec3i size = virtualLevel.getRotatedSize();

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

        PoseStack bakeStack = new PoseStack();
        RandomSource random = RandomSource.create(42L);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        int activeLayerY = SchematicPlacement.getLayerY();

        for (int y = 0; y < size.getY(); y++) {
            if (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_BELOW && y > activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_ABOVE && y < activeLayerY) continue;

            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    cursor.set(x, y, z);
                    // VirtualSchematicLevel maps rotated (x, y, z) to source coords & rotates the BlockState
                    BlockState state = virtualLevel.getBlockState(cursor);

                    if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) {
                        continue;
                    }

                    bakeStack.pushPose();
                    bakeStack.translate(x, y, z);

                    net.minecraft.world.level.block.entity.BlockEntity be = virtualLevel.getBlockEntity(cursor);
                    ModelData modelData = tankcmod.schematon.compat.CompatManager.getModelData(be);

                    blockRenderer.renderBatched(
                            state, cursor, virtualLevel, bakeStack, builder, true, random, modelData, null
                    );

                    bakeStack.popPose();
                }
            }
        }

        BufferBuilder.RenderedBuffer rendered = builder.end();
        cachedBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        cachedBuffer.bind();
        cachedBuffer.upload(rendered);
        VertexBuffer.unbind();
    }

    private static void renderBoundingBox(PoseStack poseStack, Vec3i size) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        // Use generic position-color shader to avoid 1.20's line shader view-matrix quirks
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();

        // Cyan color: R, G, B, Alpha
        float r = 0.25f, g = 0.85f, b = 1.0f, a = 0.9f;
        float maxX = size.getX();
        float maxY = size.getY();
        float maxZ = size.getZ();

        // Bottom square
        addLine(builder, matrix, 0, 0, 0, maxX, 0, 0, r, g, b, a);
        addLine(builder, matrix, maxX, 0, 0, maxX, 0, maxZ, r, g, b, a);
        addLine(builder, matrix, maxX, 0, maxZ, 0, 0, maxZ, r, g, b, a);
        addLine(builder, matrix, 0, 0, maxZ, 0, 0, 0, r, g, b, a);

        // Top square
        addLine(builder, matrix, 0, maxY, 0, maxX, maxY, 0, r, g, b, a);
        addLine(builder, matrix, maxX, maxY, 0, maxX, maxY, maxZ, r, g, b, a);
        addLine(builder, matrix, maxX, maxY, maxZ, 0, maxY, maxZ, r, g, b, a);
        addLine(builder, matrix, 0, maxY, maxZ, 0, maxY, 0, r, g, b, a);

        // Vertical pillars
        addLine(builder, matrix, 0, 0, 0, 0, maxY, 0, r, g, b, a);
        addLine(builder, matrix, maxX, 0, 0, maxX, maxY, 0, r, g, b, a);
        addLine(builder, matrix, maxX, 0, maxZ, maxX, maxY, maxZ, r, g, b, a);
        addLine(builder, matrix, 0, 0, maxZ, 0, maxY, maxZ, r, g, b, a);

        tesselator.end();

        RenderSystem.enableCull();
    }

    private static void addLine(BufferBuilder builder, Matrix4f matrix, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
        builder.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
    }

    public static void releaseBuffer() {
        if (cachedBuffer != null) {
            cachedBuffer.close();
            cachedBuffer = null;
        }
    }

    private static void renderValidationOverlays(PoseStack poseStack, VirtualSchematicLevel virtualLevel, BlockPos origin) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3i size = virtualLevel.getRotatedSize();
        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        int activeLayerY = SchematicPlacement.getLayerY();
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Keep depth test ON so highlights don't blindly shine through walls (keeps it minimal)
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();

        for (int y = 0; y < size.getY(); y++) {
            if (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_BELOW && y > activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_ABOVE && y < activeLayerY) continue;

            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    localPos.set(x, y, z);
                    BlockState schState = virtualLevel.getBlockState(localPos);
                    BlockPos worldPos = origin.offset(x, y, z);
                    BlockState worldState = mc.level.getBlockState(worldPos);

                    // 1. Air (Schem) + Air (World) -> Perfect match, do nothing.
                    if (schState.isAir() && worldState.isAir()) {
                        continue;
                    }

                    // 2. Block (Schem) + Air (World) -> Needs building. Ghost block handles this, no overlay needed.
                    if (!schState.isAir() && worldState.isAir()) {
                        continue;
                    }

                    // 3. Air (Schem) + Block/Fluid (World) -> Needs excavation/clearing.
                    if (schState.isAir() && !worldState.isAir()) {
                        if (SchematicPlacement.isExcavateAir()) {
                            // Magenta for excavation: R=1.0, G=0.0, B=1.0
                            drawMinimalHighlightBox(builder, matrix, x, y, z, 1.0f, 0.0f, 1.0f, 0.6f);
                        }
                        continue;
                    }

                    // 4. Block (Schem) + Block/Fluid (World) -> Physical collision. Check for a match.
                    // (Using schState.getBlock() == worldState.getBlock() to ignore orientation/facing per your snippet)
                    boolean isMatch = (schState.getBlock() == worldState.getBlock());

                    float r = isMatch ? 0.2f : 1.0f;
                    float g = isMatch ? 1.0f : 0.2f;
                    float b = 0.2f;
                    float a = 0.6f; // Faint transparency to keep it minimal

                    drawMinimalHighlightBox(builder, matrix, x, y, z, r, g, b, a);
                }
            }
        }

        tesselator.end();
    }

    private static void drawMinimalHighlightBox(BufferBuilder builder, Matrix4f matrix, int x, int y, int z, float r, float g, float b, float a) {
        // Inflate slightly (0.005) to prevent Z-fighting/flickering against solid block textures
        float min = -0.005f;
        float max = 1.005f;

        float x1 = x + min, y1 = y + min, z1 = z + min;
        float x2 = x + max, y2 = y + max, z2 = z + max;

        // Bottom
        addLine(builder, matrix, x1, y1, z1, x2, y1, z1, r, g, b, a);
        addLine(builder, matrix, x2, y1, z1, x2, y1, z2, r, g, b, a);
        addLine(builder, matrix, x2, y1, z2, x1, y1, z2, r, g, b, a);
        addLine(builder, matrix, x1, y1, z2, x1, y1, z1, r, g, b, a);
        // Top
        addLine(builder, matrix, x1, y2, z1, x2, y2, z1, r, g, b, a);
        addLine(builder, matrix, x2, y2, z1, x2, y2, z2, r, g, b, a);
        addLine(builder, matrix, x2, y2, z2, x1, y2, z2, r, g, b, a);
        addLine(builder, matrix, x1, y2, z2, x1, y2, z1, r, g, b, a);
        // Sides
        addLine(builder, matrix, x1, y1, z1, x1, y2, z1, r, g, b, a);
        addLine(builder, matrix, x2, y1, z1, x2, y2, z1, r, g, b, a);
        addLine(builder, matrix, x2, y1, z2, x2, y2, z2, r, g, b, a);
        addLine(builder, matrix, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }
}