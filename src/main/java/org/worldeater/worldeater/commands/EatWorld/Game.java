package org.worldeater.worldeater.commands.EatWorld;

import org.apache.commons.io.FileUtils;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.worldeater.worldeater.PlayerState;
import org.worldeater.worldeater.WorldEater;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class Game {
    private final static ArrayList<Game> instances = new ArrayList<>();

    enum GameStatus {
        AWAITING_START,
        RUNNING,
        ENDED
    }

    private final Game instance;
    public final int gameId;
    protected GameStatus status;
    protected final ArrayList<Player> players, hiders, frozenPlayers, spectators;
    private static final int maxPlayers = 10;
    protected World world;
    protected ArrayList<BukkitTask> bukkitTasks;
    protected boolean debug;
    private final Events eventListener;

    protected static ArrayList<Game> getInstances() {
        return instances;
    }

    public Game() {
        instance = this;
        instances.add(this);
        gameId = ThreadLocalRandom.current().nextInt((int) Math.pow(10, 3), (int) Math.pow(10, 4));

        players = new ArrayList<>();

        eventListener = new Events(this);
        WorldEater.getPlugin().getServer().getPluginManager().registerEvents(eventListener, WorldEater.getPlugin());

        status = GameStatus.AWAITING_START;

        hiders = new ArrayList<>();
        frozenPlayers = new ArrayList<>();
        spectators = new ArrayList<>();
        bukkitTasks = new ArrayList<>();

        int startDelay = 20;

        sendBroadcast("§6A game of WorldEater is starting in §c" + startDelay + "§6 seconds.");
        sendBroadcast("§6To join the game, type §f/eatworld join " + gameId + "§6.");

        for(int i = startDelay; i > 0; i -= 5) {
            int finalI = i;
            bukkitTasks.add(new BukkitRunnable() {
                @Override
                public void run() {
                    sendSound(Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 1, 0.8f);
                    sendGameMessage("§4[§c§l!§4] §6Game will start in §c" + finalI + " seconds§6.");
                }
            }.runTaskLater(WorldEater.getPlugin(), 20L * (startDelay - i)));
        }

        bukkitTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                gameBuilder();
            }
        }.runTaskLater(WorldEater.getPlugin(), 20 * startDelay));
    }

    private void gameBuilder() {
        status = GameStatus.RUNNING;

        sendSound(Sound.ENTITY_TNT_PRIMED, 1, 0.5f);

        if((!debug || players.size() == 0) && players.size() < 2) {
            players.clear();
            stopHard(false);

            sendBroadcast("§cNot enough players joined. Game aborted.");
            return;
        }

        sendGameMessage("Preparing game...");
        sendGameMessage("Creating normal world...");

        String worldName = "WorldEater_Game_" + gameId;

        WorldCreator normalWorldCreator = new WorldCreator(worldName + "T");
        normalWorldCreator.type(WorldType.NORMAL);
        normalWorldCreator.generateStructures(true);
        normalWorldCreator.biomeProvider(new BiomeProvider() {
            @SuppressWarnings("all")
            @Override
            public Biome getBiome(WorldInfo worldInfo, int i, int i1, int i2) {
                return Biome.DARK_FOREST;
            }

            @SuppressWarnings("all")
            @Override
            public List<Biome> getBiomes(WorldInfo worldInfo) {
                List<Biome> result = new ArrayList<>();
                result.add(Biome.DARK_FOREST);
                return result;
            }
        });

        World normalWorld = normalWorldCreator.createWorld();

        sendGameMessage("Creating void world...");

        WorldCreator worldCreator = new WorldCreator(worldName);

        worldCreator.generator(new ChunkGenerator() {
            @SuppressWarnings("all")
            @Override
            public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
                return createChunkData(world);
            }
        });

        world = worldCreator.createWorld();

        // Clone chunk

        assert normalWorld != null;

        int chunkIdx = 0;
        Chunk normalChunk = null;

        sendGameMessage("Finding a good chunk...");

        do {
            if(chunkIdx > 100) {
                sendGameMessage("Too many attempts! Gave up trying to find a good chunk.");
                break;
            }

            normalChunk = normalWorld.getChunkAt(chunkIdx, chunkIdx);
            chunkIdx += 5;
        } while(isChunkFlooded(normalWorld, normalChunk));

        Chunk chunk = world.getChunkAt(0, 0);

        sendGameMessage("Cloning chunk...");

        for(int x = 0; x < 16; x++)
            for(int z = 0; z < 16; z++)
                for(int y = -64; y <= normalWorld.getHighestBlockYAt((normalChunk.getX() << 4) + x, (normalChunk.getZ() << 4) + z); y++) {
                    Material sourceMaterial = normalChunk.getBlock((normalChunk.getX() << 4) + x, y, (normalChunk.getZ() << 4) + z).getType();
                    if(!sourceMaterial.isAir())
                        chunk.getBlock(x, y, z).setType(sourceMaterial);
                }

        sendGameMessage("Unloading normal world...");

        WorldEater.getPlugin().getServer().unloadWorld(normalWorld, false);

        sendGameMessage("Deleting normal world...");

        try {
            FileUtils.deleteDirectory(normalWorld.getWorldFolder());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        world.setPVP(false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);

        sendGameMessage("Initializing animals...");

        for(int i = 0; i < 4; i++)
            spawnEntityInNaturalHabitat(EntityType.COW);

        for(int i = 0; i < 3; i++)
            spawnEntityInNaturalHabitat(EntityType.SHEEP);

        for(int i = 0; i < 2; i++)
            spawnEntityInNaturalHabitat(EntityType.CHICKEN);

        sendGameMessage("Preparing players...");

        TeamSelectionScreen teamSelectionScreen = new TeamSelectionScreen();
        teamSelectionScreen.hiders.add(getRandomPlayer()); // Set random hider.
        teamSelectionScreen.seekers.addAll(players); // Add all players as seekers.
        teamSelectionScreen.seekers.remove(teamSelectionScreen.hiders.get(0)); // Remove hider from seeker list.
        teamSelectionScreen.update();

        for(Player eachPlayer : players) {
            eachPlayer.teleport(getSpawnLocation().add(0, 2, 0));

            Scoreboard score = Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard();

            Team t = score.getTeam("nhide");
            if(t == null) {
                t = score.registerNewTeam("nhide");
                t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            t.addEntry(eachPlayer.getName());

            try {
                new PlayerState(eachPlayer).saveState();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            PlayerState.prepareDefault(eachPlayer);

            eachPlayer.setAllowFlight(true);
            eachPlayer.setFlying(true);

            eachPlayer.openInventory(teamSelectionScreen.getInventory());
        }

        frozenPlayers.addAll(players);

        int selectTeamTime = !debug ? 20 : 5;

        for(int i = selectTeamTime; i > 0; i--) {
            int finalI = i;
            bukkitTasks.add(new BukkitRunnable() {
                @Override
                public void run() {
                    teamSelectionScreen.setTimeLeft(finalI);
                    if(finalI <= 10) sendSound(Sound.BLOCK_COMPARATOR_CLICK, 1, 0.5f);
                }
            }.runTaskLater(WorldEater.getPlugin(), 20L * (selectTeamTime - i)));
        }

        bukkitTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                gamePlay(teamSelectionScreen, chunk);
            }
        }.runTaskLater(WorldEater.getPlugin(), 20 * selectTeamTime));
    }

    private void gamePlay(TeamSelectionScreen teamSelectionScreen, Chunk chunk) {
        frozenPlayers.clear();

        hiders.addAll(teamSelectionScreen.hiders);
        teamSelectionScreen.stop();

        if(hiders.isEmpty() && !debug) {
            sendGameMessage("No one wanted to play as a hider! Picking a random hider.");
            hiders.add(getRandomPlayer());
        } else if(players.size() == hiders.size() && !debug) {
            sendGameMessage("No one wanted to play as a seeker! Picking a random seeker.");
            hiders.remove(getRandomPlayer());
        }

        for(Player eachPlayer : players) {
            eachPlayer.setAllowFlight(false);
            eachPlayer.setFlying(false);

            eachPlayer.sendTitle(
                    !hiders.contains(eachPlayer) ? "§c§lSEEKER" : "§a§lHIDER",
                    !hiders.contains(eachPlayer) ? "§eFind and eliminate the hiders." : "§eEndure the seekers attempts to kill you.", 0, 20 * 5, 0);
        }

        sendGameMessage("§aHiders are...");

        for(Player hider : hiders) {
            sendGameMessage(" - §9§l" + hider.getName());
        }

        sendGameMessage("§eGet ready! Game starts in 5 seconds...");

        bukkitTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                sendGameMessage("§eHiders are given a head start.");

                final ArrayList<BukkitTask> seekerCircleTasks = new ArrayList<>();

                for(Player eachPlayer : players) {
                    if(!hiders.contains(eachPlayer)) {
                        eachPlayer.setAllowFlight(true);
                        eachPlayer.setFlying(true);
                        eachPlayer.setInvisible(true);

                        Location center = getSpawnLocation().add(0, 20, 0);

                        double speed = 0.1;

                        final double[] angle = {0.0};

                        BukkitTask moveTask = Bukkit.getScheduler().runTaskTimer(WorldEater.getPlugin(), () -> {
                            if(!eachPlayer.isFlying())
                                eachPlayer.setFlying(true);

                            double radians = Math.toRadians(angle[0]);

                            center.setYaw((float) Math.toDegrees(Math.atan2(-Math.cos(radians), Math.sin(radians))));
                            center.setPitch(90);
                            eachPlayer.teleport(center);

                            angle[0] += speed;
                            angle[0] %= 360;
                        }, 0L, 1L);

                        bukkitTasks.add(moveTask);
                        seekerCircleTasks.add(moveTask);
                    } else {
                        eachPlayer.teleport(getSpawnLocation());
                        eachPlayer.playSound(eachPlayer, Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
                        eachPlayer.sendTitle("§c§lHURRY UP!", "§ePrepare and reach §3shelter§e fast!", 5, 20 * 5, 10);
                    }
                }

                int secondsToRelease = !debug ? 2 * 60 : 10;

                for(int i = secondsToRelease; i > 0; i--) {
                    int finalI = i;
                    bukkitTasks.add(new BukkitRunnable() {
                        @Override
                        public void run() {
                            String timeLeftString = (finalI >= 60 ? "§c" + (finalI / 60) + "§em " : "") + "§c" + finalI % 60 + "§es";

                            if(finalI % 5 == 0) {
                                sendSound(Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, (float) finalI / (secondsToRelease), (float) (finalI / (secondsToRelease)) * 1.5f + 0.5f);
                                sendGameMessage("§eSeekers are released in " + timeLeftString + ".");
                            }

                            for(Player eachPlayer : players)
                                if(!hiders.contains(eachPlayer))
                                    eachPlayer.sendTitle(timeLeftString, "§euntil released...", 0, 20 * 2, 0);
                        }
                    }.runTaskLater(WorldEater.getPlugin(), 20L * (secondsToRelease - i)));
                }

                bukkitTasks.add(new BukkitRunnable() {
                    @Override
                    public void run() {
                        for(BukkitTask task : seekerCircleTasks)
                            task.cancel();

                        world.setPVP(true);

                        frozenPlayers.clear();
                        sendGameMessage("§c§lSEEKERS HAVE BEEN RELEASED!");

                        sendSound(Sound.BLOCK_ANVIL_LAND, 2, 2);
                        for(Player eachPlayer : players) {
                            if(!hiders.contains(eachPlayer)) {
                                PlayerState.prepareDefault(eachPlayer);
                                eachPlayer.resetTitle();
                                eachPlayer.teleport(getSpawnLocation());
                            }
                        }

                        sendGameMessage("§c(!) Starting from the top of the island to the bottom, each layer of blocks will be removed at an exponential rate.");

                        int startY = 0;

                        for(int x = 0; x < 16; x++)
                            for(int z = 0; z < 16; z++)
                                startY = Math.max(startY, world.getHighestBlockYAt(x, z));

                        long progress = 0;

                        for(int y = startY; y > -50; y--) {
                            int finalY = y;
                            progress += 20L * 30 * Math.pow(0.983D, 1 + startY - y);
                            bukkitTasks.add(new BukkitRunnable() {
                                @Override
                                public void run() {
                                    sendSound(Sound.BLOCK_BAMBOO_WOOD_PRESSURE_PLATE_CLICK_OFF, 1, 1);

                                    for(int x = 0; x < 16; x++)
                                        for(int z = 0; z < 16; z++)
                                            world.setBlockData(chunk.getBlock(x, finalY, z).getLocation(), Material.AIR.createBlockData());
                                }
                            }.runTaskLater(WorldEater.getPlugin(), progress));
                        }



                        sendGameMessage("§eIf the hiders survive until the game is over, they win. Otherwise the seekers win.");

                        int gameDuration = 30;
                        for(int i = gameDuration; i >= 0; i--) {
                            int finalI = i;
                            bukkitTasks.add(new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if(finalI > 0) {
                                        if(finalI % 5 == 0 || finalI < 10)
                                            sendGameMessage("§eThe game has §c" + finalI + "§e minutes remaining.");

                                        switch(finalI) { // Timed events
                                            case 30://20:
                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_3, 1, 2f);
                                                sendGameMessage("§c§lMETEOR RAIN! §cHead to shelter!");

                                                for(int i = 0; i < 10; i++) {
                                                    bukkitTasks.add(new BukkitRunnable() {
                                                        @Override
                                                        public void run() {
                                                            Player meteorTarget = getRandomPlayer();

                                                            meteorTarget.playSound(meteorTarget, Sound.ITEM_GOAT_HORN_SOUND_0, 1, 0.5f);

                                                            Location targetLocation = meteorTarget.getLocation();

                                                            Random random = new Random();

                                                            Location meteorStart = targetLocation.clone();
                                                            meteorStart.add(random.nextInt(-50, 50), random.nextInt(50, 100), random.nextInt(-50, 50));

                                                            Fireball meteor = world.spawn(meteorStart, Fireball.class);

                                                            meteor.setIsIncendiary(true);
                                                            meteor.setYield(8);

                                                            Vector direction = targetLocation.toVector().subtract(meteorStart.toVector());

                                                            meteor.setDirection(direction);

                                                        }
                                                    }.runTaskLater(WorldEater.getPlugin(), 20 * (i + 1) * 15));
                                                }

                                                break;
                                            case 15:
                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_7, 1, 0.8f);
                                                sendGameMessage("§eAlert: Hiders are now visible for 10 seconds!");
                                                for(Player hider : hiders) {
                                                    hider.sendTitle("§c§lEXPOSED!", "§eYour location is now visible.", 5, 20 * 10, 5);
                                                    hider.addPotionEffect(
                                                            new PotionEffect(
                                                                    PotionEffectType.GLOWING, 20 * 10, 10, true, false, false
                                                            )
                                                    );
                                                }
                                                break;
                                            case 12:
                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_6, 1, 2f);
                                                sendGameMessage("§c§lHOT GROUND! §eKeep moving or you will take damage.");

                                                bukkitTasks.add(new BukkitRunnable() {
                                                    @Override
                                                    public void run() {
                                                        for(Player eachPlayer : players) {
                                                            if(eachPlayer.getVelocity().isZero()) {
                                                                eachPlayer.playSound(eachPlayer, Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, 1, 2f);
                                                                eachPlayer.damage(0.5);
                                                            }
                                                        }
                                                    }
                                                }.runTaskTimer(WorldEater.getPlugin(), 20 * 5, 20 * 5));

                                                break;
                                            case 10:
                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_6, 1, 2f);
                                                sendGameMessage("§eThe world border will shrink in §c30§e seconds!");

                                                bukkitTasks.add(new BukkitRunnable() {
                                                    @Override
                                                    public void run() {
                                                        sendSound(Sound.ITEM_GOAT_HORN_SOUND_6, 1, 0.5f);

                                                        world.getWorldBorder().setWarningTime(20);
                                                        world.getWorldBorder().setSize(32);
                                                        world.getWorldBorder().setCenter(getSpawnLocation());
                                                        sendGameMessage("§eWorld border has shrunk!");
                                                    }
                                                }.runTaskLater(WorldEater.getPlugin(), 20 * 30));
                                                break;
                                            case 5:
                                                sendGameMessage("§8<§k-§8> §4§lSUDDEN DEATH! §cExploding horses will appear. They may be killed, but - if not - they will put you down.");

                                                sendSound(Sound.ENTITY_HORSE_ANGRY, 5, 5);

                                                for(int i = 0; i < 50; i++) {
                                                    bukkitTasks.add(new BukkitRunnable() {
                                                        @Override
                                                        public void run() {
                                                            if(Math.random() * 10 < 2) {
                                                                Player unluckyPlayer = getRandomPlayer();
                                                                unluckyPlayer.playSound(unluckyPlayer, Sound.ENTITY_HORSE_ANGRY, 6, 6);

                                                                Entity horse = world.spawnEntity(unluckyPlayer.getLocation(), EntityType.HORSE);

                                                                horse.setGravity(false);
                                                                horse.setVisualFire(true);

                                                                bukkitTasks.add(new BukkitRunnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        if(!horse.isDead()) {
                                                                            world.playSound(horse.getLocation(), Sound.ENTITY_GHAST_SCREAM, 1, 0.5f);
                                                                            horse.remove();
                                                                            world.createExplosion(horse.getLocation(), 10);
                                                                        }
                                                                    }
                                                                }.runTaskLater(WorldEater.getPlugin(), 20 * 6));
                                                            }
                                                        }
                                                    }.runTaskLater(WorldEater.getPlugin(), 20 * 3 * i));
                                                }
                                                break;
                                        }
                                    } else {
                                        sendGameMessage("§aTime has gone out! Hiders win.");
                                        stop(true);
                                    }
                                }
                            }.runTaskLater(WorldEater.getPlugin(), 20L * 60 * (gameDuration - i)));
                        }
                    }
                }.runTaskLater(WorldEater.getPlugin(), 20 * secondsToRelease));
            }
        }.runTaskLater(WorldEater.getPlugin(), 20 * 5));
    }

    protected void sendGameMessage(String s) {
        ArrayList<Player> broadcastTo = new ArrayList<>();
        broadcastTo.addAll(players);
        broadcastTo.addAll(spectators);
        for(Player eachPlayer : broadcastTo)
            WorldEater.sendMessage(eachPlayer, s);
    }

    protected void sendBroadcast(String s) {
        WorldEater.sendBroadcast("§8§l[ §7#" + gameId + " §8§l]§7 " + s);
    }

    protected void sendSound(Sound sound, float v, float v1) {
        ArrayList<Player> broadcastTo = new ArrayList<>();
        broadcastTo.addAll(players);
        broadcastTo.addAll(spectators);
        for(Player eachPlayer : broadcastTo)
            eachPlayer.playSound(eachPlayer, sound, v, v1);
    }

    protected void stopHard(boolean wait) {
        if(status == GameStatus.ENDED) return;

        status = GameStatus.ENDED;

        for(BukkitTask bukkitTask : bukkitTasks)
            bukkitTask.cancel();

        if(world != null)
            Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard().resetScores("nhide");

        if(eventListener != null)
            HandlerList.unregisterAll(eventListener);

        new BukkitRunnable() {
            @Override
            public void run() {
                if(world != null) {
                    sendGameMessage("Restoring players...");

                    ArrayList<Player> restorePlayers = new ArrayList<>();
                    restorePlayers.addAll(world.getPlayers());
                    restorePlayers.addAll(players);

                    for(Player player : restorePlayers) {
                        player.removeScoreboardTag("nhide");
                        PlayerState.restoreState(player);

                        if(player.getWorld() == world)
                            player.teleport(WorldEater.getPlugin().getServer().getWorlds().get(0).getSpawnLocation());
                    }

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sendGameMessage("Unloading world...");

                            WorldEater.getPlugin().getServer().unloadWorld(world, true);

                            sendGameMessage("Deleting world...");

                            try {
                                FileUtils.deleteDirectory(world.getWorldFolder());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                            world = null;

                            sendGameMessage("Goodbye!");

                            players.clear();
                        }
                    }.runTaskLater(WorldEater.getPlugin(), 20 * 2);
                }

                frozenPlayers.clear();
                spectators.clear();
                hiders.clear();
                bukkitTasks.clear();
                debug = false;

                instances.remove(instance);
            }
        }.runTaskLater(WorldEater.getPlugin(), wait ? 20 * 10 : 0);
    }

    protected void stop(boolean hiderWins) {
        if(status == GameStatus.ENDED) return;

        stopHard(true);

        sendSound(Sound.BLOCK_BELL_USE, 3, 3);

        for(Player eachPlayer : players)
            eachPlayer.sendTitle((!hiderWins && !hiders.contains(eachPlayer)) || (hiderWins && hiders.contains(eachPlayer)) ? "§a§lVictory!" : "§c§lLost!", !hiderWins ? "§eSeekers won." : "§eHiders won.", 0, 20 * 6, 0);

        sendGameMessage("§aThe game has ended. Thanks for playing.");

        for(int i = 0; i < 10; i++) {
            double x = Math.random() * 48 - 16, z = Math.random() * 48 - 16;
            world.spawnEntity(new Location(world, x, world.getHighestBlockYAt((int) x, (int) z), z), EntityType.FIREWORK);
        }
    }

    protected void playerJoin(Player player, boolean spectator) {
        if(spectator) {
            PlayerState.restoreState(player);

            WorldEater.sendMessage(player, "Joining game as spectator, please wait...");

            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(getSpawnLocation());

            sendSound(Sound.ENTITY_CAT_HISS, 1, 0.5f);

            sendGameMessage("§e" + player.getName() + "§7 is watching as a spectator.");
            WorldEater.sendMessage(player, "Type §e/eatworld leave§7 to stop spectating.");

            spectators.add(player);
            return;
        }

        if(status != GameStatus.AWAITING_START) {
            WorldEater.sendMessage(player, "§cThis game has already started. If you wish to spectate this game, use §e/eatworld join " + gameId + " spectate§c.");
            return;
        }

        if(players.size() >= maxPlayers) {
            WorldEater.sendMessage(player, "§cGame is full!");
            return;
        }

        players.add(player);
        WorldEater.sendMessage(player, "§aYou joined! Please wait for the game to start. Type §e/eatworld leave§a to leave the queue.");
        sendBroadcast("§a" + player.getName() + "§7 joined the game queue (§6" + players.size() + "§7/§6" + maxPlayers + "§7).");

        if(players.size() == maxPlayers)
            sendBroadcast("§aThe game has been filled!");
    }

    protected void playerLeave(Player player) {
        if(players.contains(player)) {
            players.remove(player);
            PlayerState.restoreState(player);

            if(status == GameStatus.AWAITING_START) {
                sendGameMessage("§cA player in queue left.");
            } else if(status == GameStatus.RUNNING) {
                sendGameMessage("§c" + player.getName() + " quit the game!");

                if(hiders.contains(player)) { // Hider left.
                    hiders.remove(player);

                    if(hiders.isEmpty()) { // No hider remain.
                        sendGameMessage("§cThe hider has left the game, so the game is over.");
                        stop(false);
                    }
                } else if(players.size() == hiders.size()) { // Only hiders remain.
                    sendGameMessage("§cThere is no seeker remaining, so the game is over.");
                    stop(true);
                } else if(players.size() == 1) { // Only 1 player remain.
                    sendGameMessage("§cEverybody else quit. The game is over. :(");
                    stop(hiders.contains(players.get(0)));
                }
            }
            WorldEater.sendMessage(player, "§cYou left the game.");
        } else if(spectators.contains(player)) {
            spectators.remove(player);
            PlayerState.restoreState(player);

            sendGameMessage("Spectator §e" + player.getName() + "§7 left.");
            WorldEater.sendMessage(player, "§cYou stopped spectating the game.");
        }
    }

    protected Location getSpawnLocation() {
        return getSpawnLocation(new Location(world, 8, world.getHighestBlockYAt(8, 8), 8));
    }

    protected Location getSpawnLocation(Location spawn) {
        while(true) {
            if(spawn.getBlock().getType().isAir() || spawn.getBlock().getType().toString().endsWith("_LEAVES"))
                spawn.subtract(0, 1, 0); // Go down 1 block if air or leaf block.
            else if(spawn.getBlock().getType().toString().endsWith("_LOG"))
                spawn.add(1, 0, 0); // Go x++ if wood log block.
            else break;
        }

        return spawn.add(0, 2, 0);
    }

    private Player getRandomPlayer() {
        return (Player) players.toArray()[(int) (players.size() * Math.random())];
    }

    private static boolean isChunkFlooded(World world, Chunk chunk) {
        int floodPoints = 0;

        for(int x = 0; x < 16; x++)
            for(int z = 0; z < 16; z++)
                if(chunk.getBlock(x, world.getHighestBlockYAt((chunk.getX() << 4) + x, (chunk.getZ() << 4) + z) + 1, z).isLiquid())
                    floodPoints++;

        return floodPoints > 10;
    }

    private void spawnEntityInNaturalHabitat(EntityType type) {
        Location spawn = getSpawnLocation();

        Random random = new Random();

        spawn.setX(random.nextInt(3, 14));
        spawn.setZ(random.nextInt(3, 14));
        spawn.setY(world.getHighestBlockYAt((int) spawn.getX(), (int) spawn.getZ()));

        world.spawnEntity(getSpawnLocation(spawn), type);
    }
}
