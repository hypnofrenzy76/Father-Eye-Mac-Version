package io.fathereye.bridge;

import io.fathereye.bridge.util.Constants;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point. The interesting work happens in
 * {@link io.fathereye.bridge.lifecycle.BridgeLifecycle}, which is wired
 * via the static {@code @Mod.EventBusSubscriber} on the Forge bus.
 */
@Mod(Constants.MOD_ID)
public final class FatherEyeBridge {

    private static final Logger LOG = LogManager.getLogger("FatherEye-Bridge");

    public FatherEyeBridge() {
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("Father Eye Bridge {} constructing (protocol v{}).",
                Constants.BRIDGE_VERSION, Constants.PROTOCOL_VERSION);
    }
}
