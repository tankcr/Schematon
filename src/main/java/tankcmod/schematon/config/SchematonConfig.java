package tankcmod.schematon.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class SchematonConfig {
    public static final ForgeConfigSpec SPEC;

    public enum CreateMode {
        All,
        Empty
    }

    public enum PrintMode {
        GameType,
        Survival
    }

    public enum PasteMode {
        OpOnly,
        All,
        Disabled
    }

    public static final ForgeConfigSpec.EnumValue<CreateMode> ALLOW_CREATE;
    public static final ForgeConfigSpec.EnumValue<PrintMode> ALLOW_PRINT;
    public static final ForgeConfigSpec.EnumValue<PasteMode> ALLOW_PASTE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("permissions");

        ALLOW_CREATE = builder
                .comment(
                        "Controls schematic creation permissions.",
                        "Options:",
                        "  All   - allows users to create schematics with all functionality",
                        "  Empty - allows users to create schematics but no item stacks (aka machine and chest items) are included"
                )
                .defineEnum("AllowCreate", CreateMode.All);

        ALLOW_PRINT = builder
                .comment(
                        "Controls schematic printing permissions.",
                        "Options:",
                        "  GameType - If a user can go into creative then this will allow them to print anything in the schematic",
                        "  Survival - allows user to print anything in their inventory regardless of gamemode"
                )
                .defineEnum("AllowPrint", PrintMode.GameType);

        ALLOW_PASTE = builder
                .comment(
                        "Controls schematic paste permissions.",
                        "Options:",
                        "  OpOnly   - only allows users who have op to paste",
                        "  All      - Overrides server permissions and pastes schematics for any user",
                        "  Disabled - Disables Paste Mode completely"
                )
                .defineEnum("AllowPaste", PasteMode.OpOnly);

        builder.pop();
        SPEC = builder.build();
    }

    private SchematonConfig() {
    }
}