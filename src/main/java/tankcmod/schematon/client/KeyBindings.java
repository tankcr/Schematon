package tankcmod.schematon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public class KeyBindings {
    public static final String CATEGORY = "key.categories.schematon";

    // Defaults to '0'
    public static final KeyMapping KEY_LOAD_GUI = new KeyMapping(
            "key.schematon.load",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_0,
            CATEGORY
    );

    // Defaults to numpad '/'
    public static final KeyMapping KEY_CONTROL_GUI = new KeyMapping(
            "key.schematon.control",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_DIVIDE,
            CATEGORY
    );
}
