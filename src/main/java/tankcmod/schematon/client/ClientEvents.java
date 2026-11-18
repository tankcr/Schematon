package tankcmod.schematon.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent; // ADDED
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent; // ADDED
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import tankcmod.schematon.Schematon;
import tankcmod.schematon.client.gui.GuiSchematicControl;
import tankcmod.schematon.client.gui.GuiSchematicLoad;
import tankcmod.schematon.client.gui.MaterialsHudOverlay; // ADDED
import tankcmod.schematon.client.render.SchematicRenderer;
import tankcmod.schematon.world.SchematicPlacement; // ADDED
import tankcmod.schematon.client.printer.SchematicPrinter;

public class ClientEvents {

    @Mod.EventBusSubscriber(modid = Schematon.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModBusEvents {
        @SubscribeEvent
        public static void onKeyRegister(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.KEY_LOAD_GUI);
            event.register(KeyBindings.KEY_CONTROL_GUI);
            event.register(KeyBindings.TOGGLE_MATERIALS_KEY); // ADDED
        }

        // ADDED: Register the Materials HUD overlay
        @SubscribeEvent
        public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("materials_hud", MaterialsHudOverlay.HUD_MATERIALS);
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
            // ADDED: Toggle Materials HUD with Numpad *
            if (KeyBindings.TOGGLE_MATERIALS_KEY.consumeClick()) {
                SchematicPlacement.toggleMaterialsHud();
                if (SchematicPlacement.isShowMaterialsHud()) {
                    MaterialsHudOverlay.refreshCache();
                }
            }
        }

        // ADDED: Keep Materials HUD live while walking/placing blocks
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MaterialsHudOverlay.onClientTick();
            SchematicPrinter.onClientTick(SchematicPlacement.isPrinterEnabled());
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            SchematicRenderer.onRenderLevelStage(event);
        }
    }
}