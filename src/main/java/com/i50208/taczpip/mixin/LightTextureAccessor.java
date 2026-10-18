package com.i50208.taczpip.mixin;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 取光照贴图（lightmap）真正的 GL 贴图编号。
 *
 * <p>开着光影时，Sodium 内部记着“地形要采样哪张光照贴图 / 哪张方块图集”。这两个编号如果
 * 还指着旧的（已经删掉的）贴图，镜内那一遍用 Sodium 自带地形着色器画出来的地面就会是
 * 精确的纯黑 —— 实体和天空却完全正常。这里拿到当前真实在用的编号，用来校正 Sodium 的记录。
 */
@Mixin(LightTexture.class)
public interface LightTextureAccessor {

    @Accessor("lightTexture")
    DynamicTexture scopeLens$getTexture();
}
