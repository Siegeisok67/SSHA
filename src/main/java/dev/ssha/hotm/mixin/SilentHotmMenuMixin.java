package dev.ssha.hotm.mixin;

import dev.ssha.hotm.SilentHotmRead;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class SilentHotmMenuMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void ssha$hideSilentHotmMenu(Screen screen, CallbackInfo callback) {
        if (screen != null && SilentHotmRead.claim(screen)) {
            callback.cancel();
        }
    }
}
