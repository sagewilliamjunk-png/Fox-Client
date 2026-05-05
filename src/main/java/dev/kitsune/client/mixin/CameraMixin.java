package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.movement.FreeLookModule;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into {@link Camera#setup} so {@link FreeLookModule} can override
 * the camera yaw/pitch without touching the entity's real rotation. Injected
 * at TAIL so it runs after vanilla has already set the rotation from entity.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Invoker("setRotation")
    abstract void kitsune$invokeSetRotation(float yaw, float pitch);

    @Inject(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"))
    private void kitsune$applyFreeLook(Level level, Entity entity, boolean detached,
                                       boolean thirdPersonReverse, float partialTick,
                                       CallbackInfo ci) {
        try {
            FreeLookModule mod = ModuleManager.getModule(FreeLookModule.class);
            if (mod == null || !mod.isEnabled() || !mod.isFreeLookActive()) return;
            kitsune$invokeSetRotation(mod.getFreeLookYaw(), mod.getFreeLookPitch());
        } catch (Throwable t) {
            // Never break camera setup
        }
    }
}
