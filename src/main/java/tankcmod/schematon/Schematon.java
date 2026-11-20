package tankcmod.schematon;

import net.minecraftforge.fml.common.Mod;

@Mod(Schematon.MODID)
public class Schematon {
    public static final String MODID = "schematon";

    public Schematon() {
        // Client events & overlays are registered via @Mod.EventBusSubscriber(value = Dist.CLIENT) in ClientEvents
    }
}