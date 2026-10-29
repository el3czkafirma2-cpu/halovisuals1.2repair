package pl.halovisuals.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.halovisuals.Module;
import pl.halovisuals.ModuleManager;

/** Halo Nametag: golden [H] mark in front of your own name in the player list. */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void hv$tag(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Module m = ModuleManager.get("Halo Nametag");
        MinecraftClient mc = MinecraftClient.getInstance();
        if (m == null || !m.enabled || mc.player == null || mc.getNetworkHandler() == null) return;
        if (entry == mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid()))
            cir.setReturnValue(Text.literal("[H] ").formatted(Formatting.GOLD).append(cir.getReturnValue()));
    }
}
