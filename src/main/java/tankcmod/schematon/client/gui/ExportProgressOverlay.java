package tankcmod.schematon.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import tankcmod.schematon.Schematon;

@Mod.EventBusSubscriber(modid = Schematon.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExportProgressOverlay {
    private static volatile boolean active = false;
    private static volatile boolean finished = false;
    private static volatile boolean failed = false;
    private static volatile String schematicName = "";
    private static volatile String statusText = "Preparing...";
    private static volatile float progress = 0.0f; // 0.0f .. 1.0f
    private static int dismissTicksRemaining = 0;

    // Bottom-left anchor position (can be adjusted if needed)
    private static int hudX = 10;
    private static int hudYOffsetFromBottom = 64; // Leaves room above hotbar/status area
    private static final int HUD_W = 196;
    private static final int HUD_H = 38;

    private ExportProgressOverlay() {
    }

    public static void start(String name, String initialStatus) {
        Minecraft mc = Minecraft.getInstance();
        Runnable task = () -> {
            schematicName = name;
            statusText = initialStatus;
            progress = 0.0f;
            finished = false;
            failed = false;
            dismissTicksRemaining = 0;
            active = true;
        };
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    public static void updateProgress(float value, String status) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        Minecraft mc = Minecraft.getInstance();
        Runnable task = () -> {
            if (!active) return;
            progress = clamped;
            if (status != null && !status.isEmpty()) {
                statusText = status;
            }
        };
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    public static void complete(boolean success, String message) {
        Minecraft mc = Minecraft.getInstance();
        Runnable task = () -> {
            progress = success ? 1.0f : progress;
            finished = true;
            failed = !success;
            statusText = (message != null && !message.isEmpty())
                    ? message
                    : (success ? "Schematic export complete." : "Schematic export failed.");
            dismissTicksRemaining = success ? 80 : 140; // ~4s on success, ~7s on error
            active = true;
        };
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    public static void hide() {
        Minecraft mc = Minecraft.getInstance();
        Runnable task = () -> {
            active = false;
            finished = false;
            failed = false;
            dismissTicksRemaining = 0;
        };
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    public static void setPositionFromBottomLeft(int x, int yOffsetFromBottom) {
        hudX = Math.max(4, x);
        hudYOffsetFromBottom = Math.max(4, yOffsetFromBottom);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!active || !finished) return;

        if (dismissTicksRemaining > 0) {
            dismissTicksRemaining--;
            if (dismissTicksRemaining == 0) {
                active = false;
                finished = false;
                failed = false;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        if (!active) return;
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui) return;

        Font font = mc.font;
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = hudX;
        int y = Math.max(4, screenH - hudYOffsetFromBottom - HUD_H);

        GuiGraphics graphics = event.getGuiGraphics();

        // Semi-transparent backdrop so the player can see through while moving around
        int bgColor = failed ? 0x88330000 : 0x77000000;
        int borderColor = failed ? 0xAAFF5555 : (finished ? 0xAA55FF55 : 0xAA55AAFF);

        graphics.fill(x, y, x + HUD_W, y + HUD_H, bgColor);
        // Thin border
        graphics.fill(x, y, x + HUD_W, y + 1, borderColor);
        graphics.fill(x, y + HUD_H - 1, x + HUD_W, y + HUD_H, borderColor);
        graphics.fill(x, y, x + 1, y + HUD_H, borderColor);
        graphics.fill(x + HUD_W - 1, y, x + HUD_W, y + HUD_H, borderColor);

        int pct = Math.round(progress * 100.0f);
        String title = "Creating Schematic" + (schematicName.isEmpty() ? "" : ": " + schematicName);
        graphics.drawString(font, truncate(font, title, HUD_W - 42), x + 6, y + 5, 0xFFFFFF, true);
        graphics.drawString(font, pct + "%", x + HUD_W - 6 - font.width(pct + "%"), y + 5, 0x55FFFF, true);

        // Progress bar track & fill
        int barX = x + 6;
        int barY = y + 17;
        int barW = HUD_W - 12;
        int barH = 7;
        graphics.fill(barX, barY, barX + barW, barY + barH, 0x99222222);

        int fillW = Math.max(0, Math.min(barW, Math.round(barW * progress)));
        int fillColor = failed ? 0xDDFF5555 : (finished ? 0xDD55FF55 : 0xDD55AAFF);
        if (fillW > 0) {
            graphics.fill(barX, barY, barX + fillW, barY + barH, fillColor);
        }

        // Status line
        String clippedStatus = truncate(font, statusText, HUD_W - 12);
        int statusColor = failed ? 0xFF7777 : (finished ? 0x88FF88 : 0xCCCCCC);
        graphics.drawString(font, clippedStatus, x + 6, y + 27, statusColor, true);
    }

    private static String truncate(Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int avail = Math.max(0, maxWidth - font.width(ellipsis));
        return font.plainSubstrByWidth(text, avail) + ellipsis;
    }
}