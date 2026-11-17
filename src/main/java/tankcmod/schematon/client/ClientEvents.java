package tankcmod.schematon.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import tankcmod.schematon.Schematon;
import tankcmod.schematon.client.gui.GuiSchematicControl;
import tankcmod.schematon.client.gui.GuiSchematicLoad;
import tankcmod.schematon.client.render.SchematicRenderer;

public class ClientEvents {

    @Mod.EventBusSubscriber(modid = Schematon.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModBusEvents {
        @SubscribeEvent
        public static void onKeyRegister(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.KEY_LOAD_GUI);
            event.register(KeyBindings.KEY_CONTROL_GUI);
        }
    }

    @Mod.EventBusSubscriber(modid = Schematon.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ClientForgeBusEvents {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) return;

            if (KeyBindings.KEY_LOAD_GUI.consumeClick()) {
                mc.setScreen(new GuiSchematicLoad());
            }
            if (KeyBindings.KEY_CONTROL_GUI.consumeClick()) {
                mc.setScreen(new GuiSchematicControl());
            }
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            SchematicRenderer.onRenderLevelStage(event);
        }
    }
}
