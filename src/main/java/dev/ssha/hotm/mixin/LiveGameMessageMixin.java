package dev.ssha.hotm.mixin;

import dev.ssha.hotm.LiveGameMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class LiveGameMessageMixin {
    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void ssha$receiveReward(ClientboundSystemChatPacket packet, CallbackInfo callback) {
        if (!packet.overlay() && Minecraft.getInstance().isSameThread()) {
            LiveGameMessages.receive(packet.content().getString());
        }
    }
}
