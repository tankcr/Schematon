package tankcmod.schematon.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import tankcmod.schematon.Schematon;
import tankcmod.schematon.client.create.SchematicSelection;
import tankcmod.schematon.client.gui.GuiSchematicControl;
import tankcmod.schematon.client.gui.GuiSchematicCreate;
import tankcmod.schematon.client.gui.GuiSchematicLoad;
import tankcmod.schematon.client.gui.MaterialsHudOverlay;
import tankcmod.schematon.client.printer.SchematicPrinter;
import tankcmod.schematon.client.render.SchematicRenderer;
import tankcmod.schematon.client.render.SelectionRenderer;
import tankcmod.schematon.world.SchematicPlacement;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class ClientEvents {

    @Mod.EventBusSubscriber(modid = Schematon.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModBusEvents {
        @SubscribeEvent
        public static void onKeyRegister(RegisterKeyMappingsEvent event) {
            event.register(KeyBindings.KEY_LOAD_GUI);
            event.register(KeyBindings.KEY_CONTROL_GUI);
            event.register(KeyBindings.TOGGLE_MATERIALS_KEY);
        }

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
                if (SchematicPlacement.getActiveSchematic() != null) {
                    mc.setScreen(new GuiSchematicControl());
                } else {
                    if (mc.player != null) {
                        SchematicSelection.ensureInitialized(mc.player.blockPosition());
                    }
                    mc.setScreen(new GuiSchematicCreate());
                }
            }
            if (KeyBindings.TOGGLE_MATERIALS_KEY.consumeClick()) {
                SchematicPlacement.toggleMaterialsHud();
                if (SchematicPlacement.isShowMaterialsHud()) {
                    MaterialsHudOverlay.refreshCache();
                }
            }
        }

        @SubscribeEvent
        public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            if (!event.getLevel().isClientSide) return;
            if (event.getHand() != InteractionHand.MAIN_HAND) return;
            if (SchematicPlacement.getActiveSchematic() != null) return;
            if (!SchematicSelection.isSelectionActive() || !SchematicSelection.isClickToSelectEnabled()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !event.getEntity().getUUID().equals(mc.player.getUUID())) return;

            // Cancel normal block interaction (opening chests, flipping levers) while placing corners
            event.setCanceled(true);
            applySelectionClick(mc, event.getPos());
        }

        @SubscribeEvent
        public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
            if (!event.getLevel().isClientSide) return;
            if (event.getHand() != InteractionHand.MAIN_HAND) return;
            if (SchematicPlacement.getActiveSchematic() != null) return;
            if (!SchematicSelection.isSelectionActive() || !SchematicSelection.isClickToSelectEnabled()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !event.getEntity().getUUID().equals(mc.player.getUUID())) return;

            BlockPos targetPos = raycastAirOrFluidPos(mc);
            if (targetPos != null) {
                applySelectionClick(mc, targetPos);
            }
        }

        @SubscribeEvent
        public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
            if (!event.getLevel().isClientSide) return;
            if (event.getHand() != InteractionHand.MAIN_HAND) return;
            if (SchematicPlacement.getActiveSchematic() != null) return;
            if (!SchematicSelection.isSelectionActive() || !SchematicSelection.isClickToSelectEnabled()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !event.getEntity().getUUID().equals(mc.player.getUUID())) return;

            // Only handle when looking at air or water (solid blocks are handled by RightClickBlock)
            HitResult fluidCheck = mc.player.pick(mc.player.getBlockReach(), 0.0f, true);
            if (fluidCheck.getType() == HitResult.Type.BLOCK && fluidCheck instanceof BlockHitResult bhr) {
                if (!mc.level.getBlockState(bhr.getBlockPos()).getFluidState().isEmpty()) {
                    event.setCanceled(true);
                    applySelectionClick(mc, bhr.getBlockPos());
                }
            } else if (fluidCheck.getType() == HitResult.Type.MISS) {
                event.setCanceled(true);
                applySelectionClick(mc, BlockPos.containing(fluidCheck.getLocation()));
            }
        }

        private static BlockPos raycastAirOrFluidPos(Minecraft mc) {
            if (mc.player == null) return null;
            // true = include fluids (water/lava), otherwise returns MISS at max reach in air
            HitResult hit = mc.player.pick(mc.player.getBlockReach(), 0.0f, true);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
                return bhr.getBlockPos();
            }
            return BlockPos.containing(hit.getLocation());
        }

        private static void applySelectionClick(Minecraft mc, BlockPos targetPos) {
            if (mc.player == null) return;
            boolean placedPos1 = SchematicSelection.isNextClickSetsPos1()
                    || (SchematicSelection.getPos1() == null && SchematicSelection.getPos2() == null);
            SchematicSelection.recordWorldClick(targetPos);

            String cornerLabel = placedPos1 ? "§9Pos 1 (Blue)" : "§cPos 2 (Red)";
            mc.player.displayClientMessage(
                    Component.literal("§7[Schematon] Set " + cornerLabel + " §7at " + targetPos.toShortString()),
                    true
            );
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MaterialsHudOverlay.onClientTick();
            SchematicPrinter.onClientTick(SchematicPlacement.isPrinterEnabled());
        }

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            SchematicRenderer.onRenderLevelStage(event);
            SelectionRenderer.onRenderLevelStage(event);
        }
    }
}