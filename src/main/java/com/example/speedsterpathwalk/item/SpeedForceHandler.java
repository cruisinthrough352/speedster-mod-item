package com.example.speedsterpathwalk.item;

import com.example.speedsterpathwalk.SpeedsterPathwalkMod;
import com.example.speedsterpathwalk.network.PathPacketCodec;
import com.example.speedsterpathwalk.path.PathResult;
import com.example.speedsterpathwalk.path.ServerAStarPathfinder;
import com.example.speedsterpathwalk.server.ServerSpeedwalkRunner;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpeedForceHandler {
    private static final Map<UUID, MarkedBlock> MARKED_BLOCKS = new HashMap<>();

    private SpeedForceHandler() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(SpeedsterPathwalkMod.MARK_BLOCK_PACKET, (server, player, handler, buf, responseSender) -> {
            BlockPos target = buf.readBlockPos();
            server.execute(() -> markBlock(player, target));
        });

        ServerPlayNetworking.registerGlobalReceiver(SpeedsterPathwalkMod.SPEEDWALK_PACKET, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> speedwalkToMarkedBlock(player));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MARKED_BLOCKS.remove(handler.player.getUuid()));
    }

    private static void markBlock(ServerPlayerEntity player, BlockPos target) {
        if (!isHoldingSpeedForce(player)) {
            player.sendMessage(Text.literal("Hold the Speed Force item to mark a block."), true);
            return;
        }

        if (!player.getServerWorld().getWorldBorder().contains(target)) {
            player.sendMessage(Text.literal("Cannot mark a block outside the world border."), true);
            return;
        }

        MARKED_BLOCKS.put(player.getUuid(), new MarkedBlock(player.getServerWorld(), target));
        syncMarkedBlock(player, target);
        player.sendMessage(Text.literal("Speed Force marked block: " + formatPos(target)), true);
    }

    private static void speedwalkToMarkedBlock(ServerPlayerEntity player) {
        if (!isHoldingSpeedForce(player)) {
            player.sendMessage(Text.literal("Hold the Speed Force item to use Speedwalk."), true);
            return;
        }

        if (ServerSpeedwalkRunner.stop(player, "Superspeed stopped.")) {
            return;
        }

        MarkedBlock marked = MARKED_BLOCKS.get(player.getUuid());
        if (marked == null) {
            player.sendMessage(Text.literal("No Speed Force block is marked."), true);
            return;
        }

        ServerWorld world = player.getServerWorld();
        if (marked.world != world) {
            player.sendMessage(Text.literal("The marked block is in another dimension."), true);
            return;
        }

        BlockPos start = player.getBlockPos();
        BlockPos target = marked.pos;
        player.sendMessage(Text.literal("Searching Speed Force path to " + formatPos(target) + "..."), true);

        PathResult result = ServerAStarPathfinder.findPath(world, start, target);
        if (!result.success()) {
            player.sendMessage(Text.literal(result.message() + " Visited nodes: " + result.visitedNodes()), false);
            return;
        }

        if (ServerPlayNetworking.canSend(player, SpeedsterPathwalkMod.START_PATH_PACKET)) {
            PacketByteBuf buf = PacketByteBufs.create();
            PathPacketCodec.writePath(buf, result.path());
            ServerPlayNetworking.send(player, SpeedsterPathwalkMod.START_PATH_PACKET, buf);
        }

        ServerSpeedwalkRunner.start(player, result.path());
        player.sendMessage(Text.literal(result.message() + " Nodes: " + result.path().size() + ", searched: " + result.visitedNodes() + ", mode: Speed Force"), false);
    }

    private static boolean isHoldingSpeedForce(ServerPlayerEntity player) {
        return isSpeedForce(player.getMainHandStack()) || isSpeedForce(player.getOffHandStack());
    }

    private static boolean isSpeedForce(ItemStack stack) {
        return stack != null && stack.isOf(SpeedsterPathwalkMod.SPEED_FORCE);
    }

    private static void syncMarkedBlock(ServerPlayerEntity player, BlockPos target) {
        if (!ServerPlayNetworking.canSend(player, SpeedsterPathwalkMod.SYNC_MARKED_BLOCK_PACKET)) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(target);
        ServerPlayNetworking.send(player, SpeedsterPathwalkMod.SYNC_MARKED_BLOCK_PACKET, buf);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private record MarkedBlock(ServerWorld world, BlockPos pos) {
    }
}
