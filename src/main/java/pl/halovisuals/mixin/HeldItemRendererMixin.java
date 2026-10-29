package pl.halovisuals.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.halovisuals.Module;
import pl.halovisuals.ModuleManager;

/** Swing Animations: extra smooth transforms applied to the first-person item while swinging. */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void hv$swing(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress,
                          ItemStack item, float equipProgress, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                          int light, CallbackInfo ci) {
        Module m = ModuleManager.get("Swing Animations");
        if (m == null || !m.enabled || hand != Hand.MAIN_HAND || swingProgress <= 0f) return;
        float k = (float) m.num("Strength");
        float s = MathHelper.sin(swingProgress * (float) Math.PI);
        switch (m.mode("Style")) {
            case 1 -> { // Orbit
                matrices.translate(-0.15f * s * k, 0.05f * s * k, 0f);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(s * 38f * k));
            }
            case 2 -> matrices.translate(0f, 0.14f * s * k, -0.22f * s * k); // Float
            case 3 -> matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(swingProgress * 360f * 0.35f * k)); // Spin
            default -> { // Smooth Slash
                matrices.translate(-0.08f * s * k, 0f, 0f);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-s * 20f * k));
            }
        }
    }
}
