package pl.halovisuals.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.halovisuals.Features;

@Mixin(ClientPlayNetworkHandler.class)
public class NetHandlerMixin {
    @Inject(method = "onEntityStatus", at = @At("HEAD"))
    private void hv$totem(EntityStatusS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!mc.isOnThread() || mc.world == null) return; // packet handlers run twice (netty thread + main thread)
        if (packet.getStatus() == 35) {
            Entity e = packet.getEntity(mc.world);
            if (e != null) Features.totemPop(e);
        }
    }
}
