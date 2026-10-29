package pl.halovisuals.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.halovisuals.Module;
import pl.halovisuals.ModuleManager;

@Mixin(GameRenderer.class)
public class AspectMixin {
    @Inject(method = "getBasicProjectionMatrix", at = @At("RETURN"))
    private void hv$aspect(float fov, CallbackInfoReturnable<Matrix4f> cir) {
        Module m = ModuleManager.get("Aspect Ratio");
        if (m == null || !m.enabled) return;
        Window w = MinecraftClient.getInstance().getWindow();
        float current = (float) w.getFramebufferWidth() / (float) w.getFramebufferHeight();
        cir.getReturnValue().scale(current / (float) m.num("Ratio"), 1f, 1f);
    }
}
