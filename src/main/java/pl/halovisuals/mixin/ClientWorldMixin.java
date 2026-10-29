package pl.halovisuals.mixin;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.halovisuals.Module;
import pl.halovisuals.ModuleManager;

/** Custom Sky: tints the sky colour. Works whether getSkyColor returns an int (ARGB) or a Vec3d in this version. */
@Mixin(ClientWorld.class)
public class ClientWorldMixin {
    private static final float[][] PAL = {{1.00f, 0.78f, 0.42f}, {0.96f, 0.94f, 1.00f}, {1.00f, 0.60f, 0.74f}, {0.52f, 0.40f, 0.92f}};

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void hv$sky(Vec3d cameraPos, float tickDelta, CallbackInfoReturnable<Object> cir) {
        Module m = ModuleManager.get("Custom Sky");
        if (m == null || !m.enabled) return;
        float k = (float) m.num("Intensity");
        float[] p = PAL[Math.max(0, Math.min(PAL.length - 1, m.mode("Style")))];
        Object r = cir.getReturnValue();
        if (r instanceof Integer i) {
            float r0 = ((i >> 16) & 255) / 255f, g0 = ((i >> 8) & 255) / 255f, b0 = (i & 255) / 255f;
            float day = Math.min(1f, Math.max(r0, Math.max(g0, b0)));
            int nr = (int) ((r0 + (p[0] * day - r0) * k) * 255), ng = (int) ((g0 + (p[1] * day - g0) * k) * 255), nb = (int) ((b0 + (p[2] * day - b0) * k) * 255);
            cir.setReturnValue((i & 0xFF000000) | (nr << 16) | (ng << 8) | nb);
        } else if (r instanceof Vec3d v) {
            float day = (float) Math.min(1.0, Math.max(v.x, Math.max(v.y, v.z)));
            cir.setReturnValue(new Vec3d(v.x + (p[0] * day - v.x) * k, v.y + (p[1] * day - v.y) * k, v.z + (p[2] * day - v.z) * k));
        }
    }
}
