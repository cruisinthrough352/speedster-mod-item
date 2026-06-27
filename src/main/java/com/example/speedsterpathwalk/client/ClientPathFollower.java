package com.example.speedsterpathwalk.client;

import com.example.speedsterpathwalk.SpeedsterPathwalkMod;
import com.example.speedsterpathwalk.network.PathPacketCodec;
import com.example.speedsterpathwalk.mixin.LivingEntityAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LimbAnimator;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public final class ClientPathFollower {
    private static final float SPRINT_LIMB_SPEED = 1.0F;
    private static final float SPRINT_LIMB_MULTIPLIER = 1.0F;

    private static boolean animationAssistActive = false;

    private ClientPathFollower() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SpeedsterPathwalkMod.START_PATH_PACKET, (client, handler, buf, responseSender) -> {
            List<BlockPos> path = PathPacketCodec.readPath(buf);
            client.execute(() -> start(client, path));
        });

        ClientPlayNetworking.registerGlobalReceiver(SpeedsterPathwalkMod.STOP_PATH_PACKET, (client, handler, buf, responseSender) -> client.execute(() -> stop(client, "Superspeed released.")));

        ClientTickEvents.END_CLIENT_TICK.register(ClientPathFollower::tickAnimationAssist);
    }

    private static void start(MinecraftClient client, List<BlockPos> path) {
        animationAssistActive = true;
        if (client.player != null) {
            client.player.setSprinting(true);
            forceSprintLimbAnimation(client.player);
            client.player.sendMessage(Text.literal("Superspeed route locked. Server is moving you. Nodes: " + path.size()), true);
        }
    }

    private static void stop(MinecraftClient client, String message) {
        animationAssistActive = false;
        if (client.player != null) {
            client.player.setSprinting(false);
        }
        if (message != null && client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }

    private static void tickAnimationAssist(MinecraftClient client) {
        if (!animationAssistActive) {
            return;
        }

        if (client.player == null || client.world == null) {
            animationAssistActive = false;
            return;
        }

        ClientPlayerEntity player = client.player;
        player.setSprinting(true);
        forceSprintLimbAnimation(player);
    }

    private static void forceSprintLimbAnimation(ClientPlayerEntity player) {
        LimbAnimator limbAnimator = ((LivingEntityAccessor) player).speedster_pathwalk$getLimbAnimator();
        limbAnimator.setSpeed(SPRINT_LIMB_SPEED);
        limbAnimator.updateLimbs(SPRINT_LIMB_SPEED, SPRINT_LIMB_MULTIPLIER);
    }

}
