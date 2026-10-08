package dev.ssha.hotm.mixin;

import dev.ssha.hotm.SkyblockerCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "de.hysky.skyblocker.skyblock.tabhud.widget.TabHudWidget", remap = false)
public abstract class SkyblockerCommissionMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void ssha$hideDuplicate(CallbackInfoReturnable<Boolean> result) {
        if (getClass().getName().equals("de.hysky.skyblocker.skyblock.tabhud.widget.CommsWidget")
                && SkyblockerCompatibility.suppressCommissions()) {
            result.setReturnValue(false);
        }
    }
}
