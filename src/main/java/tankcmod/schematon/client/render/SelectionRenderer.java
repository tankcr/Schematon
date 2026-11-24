package tankcmod.schematon.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import tankcmod.schematon.client.create.SchematicSelection;
import tankcmod.schematon.world.SchematicPlacement;

public class SelectionRenderer {
    private static boolean dirty = true;

    public static void markDirty() {
        dirty = true;
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        // Only render creation corners when no schematic is currently loaded
        if (SchematicPlacement.getActiveSchematic() != null) {
            return;
        }

        if (!SchematicSelection.isSelectionActive()) {
            return;
        }

        BlockPos pos1 = SchematicSelection.getPos1();
        BlockPos pos2 = SchematicSelection.getPos2();
        if (pos1 == null && pos2 == null) {
            return;
        }

        dirty = false;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.5f, -1.5f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = poseStack.last().pose();

        // Overall selection bounding box (bright cyan-white) when both corners exist
        BlockPos min = SchematicSelection.getMinPos();
        BlockPos max = SchematicSelection.getMaxPos();
        if (min != null && max != null) {
            drawBoxLines(
                    builder, matrix,
                    min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1.0f, max.getY() + 1.0f, max.getZ() + 1.0f,
                    0.75f, 0.95f, 1.0f, 0.75f
            );
        }

        // Pos 1: Bright Blue ("internal light" look via high-intensity bright color + expanded glow shell)
        if (pos1 != null) {
            drawGlowingCornerMarker(builder, matrix, pos1, 0.10f, 0.65f, 1.0f);
        }

        // Pos 2: Bright Red ("internal light" look via high-intensity bright color + expanded glow shell)
        if (pos2 != null) {
            drawGlowingCornerMarker(builder, matrix, pos2, 1.0f, 0.15f, 0.20f);
        }

        tesselator.end();

        RenderSystem.polygonOffset(0.0f, 0.0f);
        RenderSystem.disablePolygonOffset();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        poseStack.popPose();
    }

    private static void drawGlowingCornerMarker(BufferBuilder builder, Matrix4f matrix, BlockPos pos,
                                                float r, float g, float b) {
        // Core block outline
        drawBoxLines(
                builder, matrix,
                pos.getX() - 0.006f, pos.getY() - 0.006f, pos.getZ() - 0.006f,
                pos.getX() + 1.006f, pos.getY() + 1.006f, pos.getZ() + 1.006f,
                r, g, b, 1.0f
        );

        // Inner and outer "internal light" halo cages so corners pop clearly even in pitch-dark areas
        drawBoxLines(
                builder, matrix,
                pos.getX() + 0.15f, pos.getY() + 0.15f, pos.getZ() + 0.15f,
                pos.getX() + 0.85f, pos.getY() + 0.85f, pos.getZ() + 0.85f,
                Math.min(1.0f, r + 0.35f), Math.min(1.0f, g + 0.35f), Math.min(1.0f, b + 0.35f), 0.95f
        );
        drawBoxLines(
                builder, matrix,
                pos.getX() - 0.06f, pos.getY() - 0.06f, pos.getZ() - 0.06f,
                pos.getX() + 1.06f, pos.getY() + 1.06f, pos.getZ() + 1.06f,
                r, g, b, 0.65f
        );
    }

    private static void drawBoxLines(BufferBuilder builder, Matrix4f matrix,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float r, float g, float b, float a) {
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

        // Vertical edges
        addLine(builder, matrix, x1, y1, z1, x1, y2, z1, r, g, b, a);
        addLine(builder, matrix, x2, y1, z1, x2, y2, z1, r, g, b, a);
        addLine(builder, matrix, x2, y1, z2, x2, y2, z2, r, g, b, a);
        addLine(builder, matrix, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private static void addLine(BufferBuilder builder, Matrix4f matrix,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float r, float g, float b, float a) {
        builder.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
    }
}