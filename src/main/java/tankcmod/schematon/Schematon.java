package tankcmod.schematon;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import tankcmod.schematon.config.SchematonConfig;
import tankcmod.schematon.network.SchematonNetwork;

@Mod(Schematon.MODID)
public class Schematon {
    public static final String MODID = "schematon";

    public Schematon() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SchematonConfig.SPEC, "schematon-common.toml");
        SchematonNetwork.register();
        // Client events & overlays are registered via @Mod.EventBusSubscriber(value = Dist.CLIENT) in ClientEvents
    }
}