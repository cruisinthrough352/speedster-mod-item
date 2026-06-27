package com.example.speedsterpathwalk.server;

import com.example.speedsterpathwalk.SpeedsterPathwalkMod;
import com.example.speedsterpathwalk.path.ServerAStarPathfinder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServerSpeedwalkRunner {
    private static final double MAX_BLOCKS_PER_TICK = 2.0D;
    private static final double MIN_BLOCKS_PER_TICK = 0.55D;
    private static final int ACCELERATION_TICKS = 8;
    private static final double DECELERATION_DISTANCE = 8.0D;
    private static final float YAW_SMOOTHING = 0.35F;

    private static final double NODE_REACH_DISTANCE = 0.08D;
    private static final double FINAL_REACH_DISTANCE = 0.20D;

    private static final int FALL_DAMAGE_GRACE_TICKS = 40;

    private static final Map<UUID, ActiveRun> ACTIVE_RUNS = new HashMap<>();
    private static final Map<UUID, Integer> FALL_DAMAGE_GRACE = new HashMap<>();

    private ServerSpeedwalkRunner() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ServerSpeedwalkRunner::tickServer);
    }

    public static void start(ServerPlayerEntity player, List<BlockPos> path) {
        if (path.isEmpty()) {
            player.sendMessage(Text.literal("Cannot start superspeed: path is empty."), false);
            return;
        }

        List<Vec3d> points = new ArrayList<>(path.size());
        for (BlockPos pos : path) {
            points.add(toFeetCenter(pos));
        }

        int firstTarget = Math.min(1, points.size() - 1);
        ActiveRun run = new ActiveRun(player.getUuid(), player.getServerWorld(), points, firstTarget, player.getYaw());
        ACTIVE_RUNS.put(player.getUuid(), run);
        FALL_DAMAGE_GRACE.remove(player.getUuid());

        player.fallDistance = 0.0F;
        player.setSprinting(true);
        playStartEffects(player);
        player.sendMessage(Text.literal("Superspeed started. Nodes: " + points.size()), true);
    }

    public static boolean stop(ServerPlayerEntity player, String message) {
        boolean removed = ACTIVE_RUNS.remove(player.getUuid()) != null;
        if (removed) {
            player.setVelocity(Vec3d.ZERO);
            player.fallDistance = 0.0F;
            grantFallDamageGrace(player.getUuid());
            player.setSprinting(false);
            playStopEffects(player);
            if (ServerPlayNetworking.canSend(player, SpeedsterPathwalkMod.STOP_PATH_PACKET)) {
                ServerPlayNetworking.send(player, SpeedsterPathwalkMod.STOP_PATH_PACKET, PacketByteBufs.empty());
            }
            if (message != null) {
                player.sendMessage(Text.literal(message), true);
            }
        }
        return removed;
    }

    public static boolean isFallDamageProtected(UUID playerId) {
        return ACTIVE_RUNS.containsKey(playerId) || FALL_DAMAGE_GRACE.getOrDefault(playerId, 0) > 0;
    }

    private static void grantFallDamageGrace(UUID playerId) {
        FALL_DAMAGE_GRACE.put(playerId, FALL_DAMAGE_GRACE_TICKS);
    }

    private static void tickFallDamageGrace() {
        if (FALL_DAMAGE_GRACE.isEmpty()) {
            return;
        }

        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : FALL_DAMAGE_GRACE.entrySet()) {
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                expired.add(entry.getKey());
            } else {
                entry.setValue(remaining);
            }
        }

        for (UUID uuid : expired) {
            FALL_DAMAGE_GRACE.remove(uuid);
        }
    }

    private static void tickServer(MinecraftServer server) {
        tickFallDamageGrace();

        if (ACTIVE_RUNS.isEmpty()) {
            return;
        }

        List<UUID> toRemove = new ArrayList<>();
        for (ActiveRun run : ACTIVE_RUNS.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(run.playerId);
            if (player == null || player.isRemoved() || !player.isAlive() || player.getServerWorld() != run.world) {
                toRemove.add(run.playerId);
                continue;
            }

            TickResult result = tickRun(player, run);
            if (result.finished()) {
                toRemove.add(run.playerId);
                Vec3d finalPos = resolveFinalLandingPosition(run.world, run.points.get(run.points.size() - 1));
                teleportPlayer(player, player.getPos(), finalPos, result.yaw());
                player.setVelocity(Vec3d.ZERO);
                player.fallDistance = 0.0F;
                grantFallDamageGrace(player.getUuid());
                player.setSprinting(false);
                playStopEffects(player);
                player.sendMessage(Text.literal("Destination reached."), true);
                if (ServerPlayNetworking.canSend(player, SpeedsterPathwalkMod.STOP_PATH_PACKET)) {
                    ServerPlayNetworking.send(player, SpeedsterPathwalkMod.STOP_PATH_PACKET, PacketByteBufs.empty());
                }
            }
        }

        for (UUID uuid : toRemove) {
            ACTIVE_RUNS.remove(uuid);
        }
    }

    private static TickResult tickRun(ServerPlayerEntity player, ActiveRun run) {
        Vec3d oldPos = player.getPos();
        double distanceBudget = computeDistanceBudget(run, oldPos);

        player.setSprinting(true);
        player.fallDistance = 0.0F;
        run.ageTicks++;

        MoveStep step = advanceAlongPath(oldPos, run, distanceBudget);
        run.currentIndex = step.currentIndex();

        float yaw = run.yaw;
        if (!Float.isNaN(step.targetYaw())) {
            yaw = MathHelper.lerpAngleDegrees(YAW_SMOOTHING, run.yaw, step.targetYaw());
            run.yaw = yaw;
        }

        Vec3d newPos = step.position();
        if (oldPos.squaredDistanceTo(newPos) > 0.000001D) {
            teleportPlayer(player, oldPos, newPos, yaw);
            spawnSpeedTrail(run.world, oldPos, newPos);
        }

        return new TickResult(step.finished(), yaw);
    }

    private static double computeDistanceBudget(ActiveRun run, Vec3d currentPosition) {
        double acceleration = Math.min(1.0D, (run.ageTicks + 1) / (double) ACCELERATION_TICKS);
        double distanceRemaining = estimateRemainingDistance(run, currentPosition);
        double deceleration = Math.min(1.0D, distanceRemaining / DECELERATION_DISTANCE);
        double factor = Math.min(acceleration, Math.max(0.25D, deceleration));
        return MathHelper.clamp(MAX_BLOCKS_PER_TICK * factor, MIN_BLOCKS_PER_TICK, MAX_BLOCKS_PER_TICK);
    }

    private static double estimateRemainingDistance(ActiveRun run, Vec3d currentPosition) {
        if (run.currentIndex >= run.points.size()) {
            return 0.0D;
        }

        double distance = currentPosition.distanceTo(run.points.get(run.currentIndex));
        for (int i = run.currentIndex; i < run.points.size() - 1; i++) {
            distance += run.points.get(i).distanceTo(run.points.get(i + 1));
        }
        return distance;
    }

    private static MoveStep advanceAlongPath(Vec3d from, ActiveRun run, double distanceBudget) {
        int index = run.currentIndex;
        Vec3d position = from;
        float targetYaw = Float.NaN;
        double remaining = distanceBudget;

        while (remaining > 0.0001D && index < run.points.size()) {
            Vec3d target = run.points.get(index);
            boolean finalNode = index == run.points.size() - 1;
            double reachDistance = finalNode ? FINAL_REACH_DISTANCE : NODE_REACH_DISTANCE;
            Vec3d delta = target.subtract(position);
            double distance = delta.length();

            if (Math.abs(delta.x) > 0.0001D || Math.abs(delta.z) > 0.0001D) {
                targetYaw = (float) (Math.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
            }

            if (distance <= reachDistance) {
                if (finalNode) {
                    return new MoveStep(target, index + 1, true, targetYaw);
                }
                index++;
                continue;
            }

            double allowed = Math.max(0.0D, distance - reachDistance);
            double travel = Math.min(remaining, allowed);
            if (travel <= 0.0001D) {
                if (finalNode) {
                    return new MoveStep(target, index + 1, true, targetYaw);
                }
                index++;
                continue;
            }

            position = position.add(delta.multiply(travel / distance));
            remaining -= travel;
        }

        return new MoveStep(position, index, index >= run.points.size(), targetYaw);
    }

    private static void teleportPlayer(ServerPlayerEntity player, Vec3d oldPos, Vec3d newPos, float yaw) {
        Vec3d tickVelocity = newPos.subtract(oldPos);
        player.setVelocity(tickVelocity);
        player.fallDistance = 0.0F;
        player.setSprinting(true);
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.networkHandler.requestTeleport(newPos.x, newPos.y, newPos.z, yaw, player.getPitch());
    }

    private static void spawnSpeedTrail(ServerWorld world, Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        double distance = delta.length();
        int points = MathHelper.clamp((int) Math.ceil(distance * 3.0D), 1, 12);

        for (int i = 0; i <= points; i++) {
            double t = i / (double) points;
            Vec3d pos = from.lerp(to, t).add(0.0D, 0.85D, 0.0D);
            world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 1, 0.08D, 0.18D, 0.08D, 0.015D);
        }

        Vec3d center = from.add(to).multiply(0.5D).add(0.0D, 0.12D, 0.0D);
        world.spawnParticles(ParticleTypes.CLOUD, center.x, center.y, center.z, 2, 0.18D, 0.03D, 0.18D, 0.01D);
    }

    private static void playStartEffects(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Vec3d pos = player.getPos().add(0.0D, 0.7D, 0.0D);
        world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 20, 0.35D, 0.5D, 0.35D, 0.05D);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 0.7F, 1.6F);
    }

    private static void playStopEffects(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Vec3d pos = player.getPos().add(0.0D, 0.7D, 0.0D);
        world.spawnParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 16, 0.4D, 0.12D, 0.4D, 0.03D);
        world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 8, 0.3D, 0.4D, 0.3D, 0.04D);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 0.55F, 1.85F);
    }

    private static Vec3d resolveFinalLandingPosition(ServerWorld world, Vec3d desired) {
        BlockPos desiredFeet = BlockPos.ofFloored(desired.x, desired.y, desired.z);
        if (ServerAStarPathfinder.isStandable(world, desiredFeet)) {
            return toFeetCenter(desiredFeet);
        }

        for (int offset = 1; offset <= 3; offset++) {
            BlockPos up = desiredFeet.up(offset);
            if (ServerAStarPathfinder.isStandable(world, up)) {
                return toFeetCenter(up);
            }

            BlockPos down = desiredFeet.down(offset);
            if (ServerAStarPathfinder.isStandable(world, down)) {
                return toFeetCenter(down);
            }
        }

        return desired;
    }

    private static Vec3d toFeetCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    private static final class ActiveRun {
        private final UUID playerId;
        private final ServerWorld world;
        private final List<Vec3d> points;
        private int currentIndex;
        private int ageTicks;
        private float yaw;

        private ActiveRun(UUID playerId, ServerWorld world, List<Vec3d> points, int currentIndex, float yaw) {
            this.playerId = playerId;
            this.world = world;
            this.points = points;
            this.currentIndex = currentIndex;
            this.yaw = yaw;
        }
    }

    private record MoveStep(Vec3d position, int currentIndex, boolean finished, float targetYaw) {
    }

    private record TickResult(boolean finished, float yaw) {
    }
}
