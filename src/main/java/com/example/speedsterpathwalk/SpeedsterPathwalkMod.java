package com.example.speedsterpathwalk;

import com.example.speedsterpathwalk.item.SpeedForceHandler;
import com.example.speedsterpathwalk.server.ServerSpeedwalkRunner;
import net.fabricmc.api.ModInitializer;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class SpeedsterPathwalkMod implements ModInitializer {
    public static final String MOD_ID = "speedster_pathwalk";

    public static final Identifier START_PATH_PACKET = new Identifier(MOD_ID, "start_path");
    public static final Identifier STOP_PATH_PACKET = new Identifier(MOD_ID, "stop_path");
    public static final Identifier MARK_BLOCK_PACKET = new Identifier(MOD_ID, "mark_block");
    public static final Identifier SPEEDWALK_PACKET = new Identifier(MOD_ID, "speedwalk");
    public static final Identifier SYNC_MARKED_BLOCK_PACKET = new Identifier(MOD_ID, "sync_marked_block");

    public static final Identifier SPEED_FORCE_ID = new Identifier(MOD_ID, "speed_force");
    public static final Item SPEED_FORCE = new Item(new Item.Settings());

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, SPEED_FORCE_ID, SPEED_FORCE);
        SpeedForceHandler.register();
        ServerSpeedwalkRunner.register();
    }
}
