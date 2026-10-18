package com.i50208.taczpip.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface RenderTypeAccessor {
    @Accessor("state")
    RenderType.CompositeState scopeLens$getState();
}
