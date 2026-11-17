package tankcmod.schematon.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import tankcmod.schematon.world.SchematicPlacement;

public class SchematicRenderer {
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (SchematicPlacement.getActiveSchematic() == null) return;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        
        // Offset projection based on camera position and SchematicPlacement.getOrigin()
        // (VBO chunk rendering logic will go here)
        
        poseStack.popPose();
    }
}
