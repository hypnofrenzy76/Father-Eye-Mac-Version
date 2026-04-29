package io.fathereye.map;

import io.fathereye.mapcore.api.MapCore;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Skeleton mod entry. The parallel Claude session owns the body of this
 * subproject — see {@code map/docs/HANDOFF.md} for the contract and the
 * sketch of the {@code MapGraphics} adapter over Minecraft's RenderSystem.
 *
 * <p>What lives here today is a no-op @Mod just so the build emits a JAR
 * that loads on the server (proving the mods.toml + dependency declarations
 * are correct). Everything else — the in-game Screen, the keybind, the
 * op-gated command, the Mc-side MapGraphics — is the parallel session's
 * deliverable.
 */
@Mod("fathereye_map")
public final class FatherEyeMap {

    private static final Logger LOG = LogManager.getLogger("FatherEye-Map");

    public FatherEyeMap() {
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("Father Eye Map skeleton loaded (using mapcore {}).", MapCore.VERSION);
    }
}
