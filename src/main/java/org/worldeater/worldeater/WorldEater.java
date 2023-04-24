package org.worldeater.worldeater;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.worldeater.worldeater.commands.EatWorld.EatWorld;

public final class WorldEater extends JavaPlugin {

    private static WorldEater plugin;
    public static String messagePrefix = "§8:: §2World§6Eater §8:: §7";

    @Override
    public void onEnable() {
        plugin = this;

        getCommand("eatworld").setExecutor(new EatWorld());

        WorldEater.getPlugin().getServer().getPluginManager().registerEvents(new Events(), WorldEater.getPlugin());
        getLogger().info("WorldEater plugin has been initialized.");
    }

    @Override
    public void onDisable() {
        getLogger().info("WorldEater plugin has been stopped.");
    }

    public static WorldEater getPlugin() {
        return plugin;
    }

    private static String getFancyText(String text) {
        return messagePrefix + text;
    }

    public static void sendBroadcast(String text) {
        plugin.getServer().broadcastMessage(getFancyText(text));
    }

    public static void sendMessage(CommandSender commandSender, String text) {
        commandSender.sendMessage(getFancyText(text));
    }

    public static void sendMessage(Player player, String text) {
        player.sendMessage(getFancyText(text));
    }
}