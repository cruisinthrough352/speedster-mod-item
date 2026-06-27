package com.example.speedsterpathwalk.client;

import com.example.speedsterpathwalk.SpeedsterPathwalkMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

public final class SpeedForceClientHandler {
    private static final DustParticleEffect YELLOW_HIGHLIGHT = new DustParticleEffect(new Vector3f(1.0F, 0.95F, 0.0F), 1.25F);
    private static final double HIGHLIGHT_STEP = 0.20D;

    private static KeyBinding markBlockKey;
    private static KeyBinding speedwalkKey;
    private static BlockPos markedBlock;
    private static int highlightTicker;

    private SpeedForceClientHandler() {
    }

    public static void register() {
        markBlockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.speedster_pathwalk.mark_block",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "key.categories.speedster_pathwalk"
        ));

        speedwalkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.speedster_pathwalk.speedwalk",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.speedster_pathwalk"
        ));

        ClientPlayNetworking.registerGlobalReceiver(SpeedsterPathwalkMod.SYNC_MARKED_BLOCK_PACKET, (client, handler, buf, responseSender) -> {
            BlockPos target = buf.readBlockPos();
            client.execute(() -> markedBlock = target);
        });

        ClientTickEvents.END_CLIENT_TICK.register(SpeedForceClientHandler::tickClient);
    }

    private static void tickClient(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            markedBlock = null;
            return;
        }

        while (markBlockKey.wasPressed()) {
            markLookedAtBlock(client);
        }

        while (speedwalkKey.wasPressed()) {
            requestSpeedwalk(client);
        }

        tickHighlight(client);
    }

    private static void markLookedAtBlock(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        if (!isHoldingSpeedForce(player)) {
            player.sendMessage(Text.literal("Hold the Speed Force item to mark a block."), true);
            return;
        }

        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            player.sendMessage(Text.literal("Look at a block to mark it with the Speed Force."), true);
            return;
        }

        BlockPos target = blockHit.getBlockPos();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(target);
        ClientPlayNetworking.send(SpeedsterPathwalkMod.MARK_BLOCK_PACKET, buf);
    }

    private static void requestSpeedwalk(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        if (!isHoldingSpeedForce(player)) {
            player.sendMessage(Text.literal("Hold the Speed Force item to use Speedwalk."), true);
            return;
        }

        ClientPlayNetworking.send(SpeedsterPathwalkMod.SPEEDWALK_PACKET, PacketByteBufs.empty());
    }

    private static void tickHighlight(MinecraftClient client) {
        if (markedBlock == null || !isHoldingSpeedForce(client.player)) {
            return;
        }

        highlightTicker++;
        if (highlightTicker % 2 != 0) {
            return;
        }

        spawnYellowBlockOutline(client, markedBlock);
    }

    private static boolean isHoldingSpeedForce(ClientPlayerEntity player) {
        return player != null && (isSpeedForce(player.getMainHandStack()) || isSpeedForce(player.getOffHandStack()));
    }

    private static boolean isSpeedForce(ItemStack stack) {
        return stack != null && stack.isOf(SpeedsterPathwalkMod.SPEED_FORCE);
    }

    private static void spawnYellowBlockOutline(MinecraftClient client, BlockPos pos) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        for (double t = 0.0D; t <= 1.0001D; t += HIGHLIGHT_STEP) {
            particle(client, x + t, y, z);
            particle(client, x + t, y + 1.0D, z);
            particle(client, x + t, y, z + 1.0D);
            particle(client, x + t, y + 1.0D, z + 1.0D);

            particle(client, x, y + t, z);
            particle(client, x + 1.0D, y + t, z);
            particle(client, x, y + t, z + 1.0D);
            particle(client, x + 1.0D, y + t, z + 1.0D);

            particle(client, x, y, z + t);
            particle(client, x + 1.0D, y, z + t);
            particle(client, x, y + 1.0D, z + t);
            particle(client, x + 1.0D, y + 1.0D, z + t);
        }

        Vec3d center = Vec3d.ofCenter(pos);
        particle(client, center.x, center.y, center.z);
    }

    private static void particle(MinecraftClient client, double x, double y, double z) {
        if (client.world != null) {
            client.world.addParticle(YELLOW_HIGHLIGHT, x, y, z, 0.0D, 0.0D, 0.0D);
        }
    }
}
