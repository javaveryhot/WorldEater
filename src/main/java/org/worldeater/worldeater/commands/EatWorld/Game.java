package org.worldeater.worldeater.commands.EatWorld;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.HandlerList;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.worldeater.worldeater.PlayerState;
import org.worldeater.worldeater.WorldEater;

import java.io.File;
import java.io.IOException;
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
    protected static final int maxPlayers = 10;
    protected World world;
    protected final ArrayList<BukkitTask> bukkitTasks;
    protected boolean debug;
    private final Events eventListener;
    private Team seekersTeam;
    private Team hidersTeam;
    private Scoreboard scoreboard;
    private static final String cacheWorldName = "WORLDEATER_CACHE", cacheVoidWorldName = cacheWorldName + "_VOID";

    private enum PreparationStep {
        CREATE_NORMAL_WORLD,
        CREATE_VOID_WORLD,
        FIND_CHUNK,
        CLONE_CHUNK,
        UNLOAD_NORMAL_WORLD,
        FINAL_SETUP
    }

    private int timeLeft;
    private final ArrayList<GameEvent> events;

    private enum GameEvent {
        METEOR_RAIN("Meteor rain"),
        VISIBLE_HIDERS("Hiders are exposed"),
        DRILLING("Drilling"),
        SHRINKING_WORLD_BORDER("World border is shrinking"),
        EXPLODING_HORSES("Exploding horses are incoming");

        public final String eventName;

        GameEvent(String eventName) {
            this.eventName = eventName;
        }
    }

    protected static ArrayList<Game> getInstances() {
        return instances;
    }

    public Game(World broadcastChannel, boolean debugMode) {
        instance = this;
        instances.add(this);
        gameId = ThreadLocalRandom.current().nextInt((int) Math.pow(10, 3), (int) Math.pow(10, 4));
        debug = debugMode;

        players = new ArrayList<>();

        eventListener = new Events(this);
        WorldEater.getPlugin().getServer().getPluginManager().registerEvents(eventListener, WorldEater.getPlugin());

        status = GameStatus.AWAITING_START;

        hiders = new ArrayList<>();
        frozenPlayers = new ArrayList<>();
        spectators = new ArrayList<>();
        bukkitTasks = new ArrayList<>();
        events = new ArrayList<>();

        int startDelay = !debug ? 20 : 1;

        sendBroadcast(broadcastChannel, "§6A game starts in §c" + startDelay + "s§6. §a§l[ CLICK TO JOIN ]", "eatworld join " + gameId);

        for(int i = startDelay; i > 0; i--) {
            int finalI = i;
            bukkitTasks.add(new BukkitRunnable() {
                @Override
                public void run() {
                    sendSound(Sound.ENTITY_CHICKEN_EGG, 1, 2);
                    for(Player eachPlayer : players)
                        eachPlayer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§6§lWORLD§2§lEATER §8| §bStarting game in §6§l" + finalI + "s§b (§3" + players.size() + "§b/§3" + maxPlayers + "§b players)"));
                    if(finalI % 5 == 0)
                        sendGameMessage("§4[§c§l!§4] §6Game starts in §c" + finalI + "s§6.");
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

        sendSound(Sound.ENTITY_AXOLOTL_HURT, 1, 0.5f);

        if((!debug || players.size() == 0) && players.size() < 2) {
            sendGameMessage("§cNot enough players joined. Game aborted.");
            players.clear();
            stopHard(false);
            return;
        }

        preparingGameProgressUpdate(PreparationStep.CREATE_NORMAL_WORLD);

        sendGameMessage("Preparing game...");

        World normalWorld;

        sendGameMessage("Creating normal world...");

        WorldCreator normalWorldCreator = new WorldCreator(cacheWorldName);
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

        normalWorld = normalWorldCreator.createWorld();
        assert normalWorld != null;

        if(debug)
            sendGameMessage(String.valueOf(normalWorld.getSeed()));

        preparingGameProgressUpdate(PreparationStep.CREATE_VOID_WORLD);

        sendGameMessage("Creating void world...");

        WorldCreator worldCreator = new WorldCreator(cacheVoidWorldName);

        worldCreator.generator(new ChunkGenerator() {
            @Override
            @SuppressWarnings({"NullableProblems", "deprecation"})
            public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
                return createChunkData(world);
            }
        });

        world = worldCreator.createWorld();

        assert world != null;
        world.setPVP(false);

        // Reset world

        sendGameMessage("Resetting void world...");

        for(Entity entity : world.getChunkAt(0, 0).getEntities())
            entity.remove();

        for(int x = -64; x < 64; x++)
            for(int z = -64; z < 64; z++)
                for(int y = world.getMinHeight(); y <= world.getHighestBlockYAt(x, z); y++)
                    if(!world.getBlockAt(x, y, z).getType().isAir())
                        world.setBlockData(x, y, z, Material.AIR.createBlockData());

        world.setTime(0);
        world.setClearWeatherDuration(20 * 60 * 30);

        // Clone chunk

        Random random = new Random();

        int chunkX = 0,
                chunkY = 0,
                chunkTries = 0;
        List<Integer> chunkCoordinate = new ArrayList<>();
        Chunk normalChunk = null;

        preparingGameProgressUpdate(PreparationStep.FIND_CHUNK);

        if(debug)
            sendGameMessage("Checking bad chunk coordinates...");

        File badChunksFile = new File(WorldEater.getPlugin().getPluginDirectory(), "badChunks.yml");
        YamlConfiguration badChunksConfig = YamlConfiguration.loadConfiguration(badChunksFile);

        if(debug)
            sendGameMessage("Copying bad chunk coordinates...");

        @SuppressWarnings("unchecked")
        List<List<Integer>> badChunks = (List<List<Integer>>) badChunksConfig.getList("chunks");

        if(badChunks == null)
            badChunks = new ArrayList<>();

        if(debug)
            sendGameMessage("Found " + badChunks.size() + " bad chunks registered.");

        sendGameMessage("Finding a good chunk...");

        while(true) {
            if(chunkTries > 150) {
                sendGameMessage("Too many attempts! Gave up trying to find a good chunk.");
                break;
            }

            if(debug)
                sendGameMessage("Checking chunk (" + chunkX + ", " + chunkY + ")...");

            chunkCoordinate.clear();
            chunkCoordinate.add(chunkX);
            chunkCoordinate.add(chunkY);

            boolean registeredBadChunk = false;

            for(List<Integer> badChunkCoordinate : badChunks)
                if(badChunkCoordinate.equals(chunkCoordinate)) {
                    registeredBadChunk = true;
                    break;
                }

            if(registeredBadChunk || isChunkFlooded(normalWorld.getChunkAt(chunkX, chunkY))) {
                if(debug)
                    sendGameMessage("Bad chunk! Blacklisting.");

                badChunks.add(new ArrayList<>(chunkCoordinate));

                chunkX = random.nextInt(0, 100);
                chunkY = random.nextInt(0, 100);
                chunkTries++;
            } else {
                normalChunk = normalWorld.getChunkAt(chunkX, chunkY);
                break;
            }
        }

        if(debug)
            sendGameMessage("Selected chunk! Blacklisting.");

        badChunks.add(chunkCoordinate);

        badChunksConfig.set("chunks", badChunks);

        try {
            badChunksConfig.save(badChunksFile);
        } catch(IOException e) {
            throw new RuntimeException(e);
        }

        if(debug)
            sendGameMessage("Bad chunk register updated! " + badChunks.size() + " bad chunks registered in total.");

        Chunk chunk = world.getChunkAt(0, 0);

        preparingGameProgressUpdate(PreparationStep.CLONE_CHUNK);

        if(debug)
            sendGameMessage("Cloning chunk...");

        assert normalChunk != null;

        for(int x = 0; x < 16; x++)
            for(int z = 0; z < 16; z++)
                for(int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    Material sourceMaterial = normalChunk.getBlock(x, y, z).getType();
                    if(!sourceMaterial.isAir())
                        chunk.getBlock(x, y, z).setType(sourceMaterial);
                }

        preparingGameProgressUpdate(PreparationStep.UNLOAD_NORMAL_WORLD);

        if(debug)
            sendGameMessage("Unloading normal world...");

        WorldEater.getPlugin().getServer().unloadWorld(normalWorld, false);

        preparingGameProgressUpdate(PreparationStep.FINAL_SETUP);

        if(debug)
            sendGameMessage("Initializing animals...");

        for(int i = 0; i < 4; i++)
            spawnEntityInNaturalHabitat(EntityType.COW);

        for(int i = 0; i < 3; i++)
            spawnEntityInNaturalHabitat(EntityType.SHEEP);

        for(int i = 0; i < 2; i++)
            spawnEntityInNaturalHabitat(EntityType.CHICKEN);

        if(debug)
            sendGameMessage("Preparing players...");

        TeamSelectionScreen teamSelectionScreen = new TeamSelectionScreen(this);
        teamSelectionScreen.hiders.add(getRandomPlayer()); // Set random hider.
        teamSelectionScreen.seekers.addAll(players); // Add all players as seekers.
        teamSelectionScreen.seekers.remove(teamSelectionScreen.hiders.get(0)); // Remove hider from seeker list.
        teamSelectionScreen.update();

        Scoreboard score = Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard();

        seekersTeam = score.getTeam("seekers");
        hidersTeam = score.getTeam("hiders");

        if(seekersTeam == null)
            seekersTeam = score.registerNewTeam("seekers");

        if(hidersTeam == null)
            hidersTeam = score.registerNewTeam("hiders");

        seekersTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OTHER_TEAMS);
        seekersTeam.setCanSeeFriendlyInvisibles(false);
        seekersTeam.setAllowFriendlyFire(false);
        seekersTeam.setPrefix("§4§l[ §c§lSEEKER §4§l] ");
        seekersTeam.setColor(ChatColor.RED);

        hidersTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OTHER_TEAMS);
        hidersTeam.setCanSeeFriendlyInvisibles(false);
        hidersTeam.setAllowFriendlyFire(false);
        hidersTeam.setPrefix("§2§l[ §a§lHIDER §2§l] ");
        hidersTeam.setColor(ChatColor.GREEN);

        scoreboard = createGameScoreboard();

        for(Player eachPlayer : players) {
            eachPlayer.teleport(getSpawnLocation());
            PlayerState.prepareIdle(eachPlayer, true);

            eachPlayer.openInventory(teamSelectionScreen.getInventory());

            eachPlayer.setScoreboard(scoreboard);
        }

        frozenPlayers.addAll(players);

        int selectTeamTime = !debug ? 40 : 5;
        final BukkitTask waitForSelection;
        final ArrayList<BukkitTask> tasks = new ArrayList<>();

        bukkitTasks.add(waitForSelection = new BukkitRunnable() {
            @Override
            public void run() {
                gamePlay(teamSelectionScreen, chunk);
            }
        }.runTaskLater(WorldEater.getPlugin(), 20 * selectTeamTime));

        for(int i = selectTeamTime; i > 0; i--) {
            int finalI = i;
            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    if(teamSelectionScreen.readyPlayers.size() == players.size()) {
                        waitForSelection.cancel();

                        for(BukkitTask task : tasks)
                            task.cancel();

                        gamePlay(teamSelectionScreen, chunk);
                        return;
                    }

                    teamSelectionScreen.setTimeLeft(finalI);
                    if(finalI <= 10) sendSound(Sound.BLOCK_COMPARATOR_CLICK, 1, 0.5f);
                }
            }.runTaskLater(WorldEater.getPlugin(), 20L * (selectTeamTime - i));

            bukkitTasks.add(task);
            tasks.add(task);
        }
    }

    private void gamePlay(TeamSelectionScreen teamSelectionScreen, Chunk chunk) {
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
            PlayerState.prepareIdle(eachPlayer, true);

            eachPlayer.sendTitle(
                    !hiders.contains(eachPlayer) ? "§c§lSEEKER" : "§a§lHIDER",
                    !hiders.contains(eachPlayer) ? "§eFind and eliminate the hiders." : "§eEndure the seekers attempts to kill you.", 0, 20 * 5, 0);
        }

        sendGameMessage("§aHiders are...");

        for(Player hider : hiders)
            sendGameMessage(" - §a§l" + hider.getName());

        sendGameMessage("§eGet ready! Game starts in §c5§e seconds...");

        bukkitTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                frozenPlayers.clear();

                sendGameMessage("§eHiders are given a head start.");

                final ArrayList<BukkitTask> seekerCircleTasks = new ArrayList<>();

                for(Player eachPlayer : players) {
                    if(!hiders.contains(eachPlayer)) {
                        PlayerState.prepareIdle(eachPlayer, true);

                        Location center = getCenterTopLocation().add(0, 20, 0);

                        double speed = 0.1;

                        final double[] angle = {0};

                        BukkitTask moveTask = Bukkit.getScheduler().runTaskTimer(WorldEater.getPlugin(), () -> {
                            if(!eachPlayer.isFlying())
                                eachPlayer.setFlying(true);

                            double radians = Math.toRadians(angle[0]);

                            center.setYaw((float) Math.toDegrees(Math.atan2(-Math.cos(radians), Math.sin(radians))));
                            center.setPitch(90);
                            eachPlayer.teleport(center);

                            angle[0] += speed;
                            angle[0] %= 360;
                        }, 0, 1);

                        bukkitTasks.add(moveTask);
                        seekerCircleTasks.add(moveTask);

                        seekersTeam.addEntry(eachPlayer.getName());
                    } else {
                        PlayerState.prepareNormal(eachPlayer, true);

                        eachPlayer.teleport(getSpawnLocation());
                        eachPlayer.playSound(eachPlayer, Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
                        eachPlayer.sendTitle("§c§lHURRY UP!", "§ePrepare and reach §3shelter§e fast!", 5, 20 * 5, 10);

                        hidersTeam.addEntry(eachPlayer.getName());
                    }
                }

                int secondsToRelease = !debug ? 2 * 60 : 10;

                for(int i = secondsToRelease; i > 0; i--) {
                    int finalI = i;
                    bukkitTasks.add(new BukkitRunnable() {
                        @Override
                        public void run() {
                            String timeLeftString = getFancyTimeLeft(finalI);

                            if(finalI % 2 == 0)
                                sendSound(Sound.BLOCK_POINTED_DRIPSTONE_FALL, 0.6f, 2);

                            if(finalI % 10 == 0)
                                sendGameMessage("§eSeekers are released in " + timeLeftString + ".");

                            for(Player eachPlayer : players) {
                                if(!hiders.contains(eachPlayer))
                                    eachPlayer.sendTitle(timeLeftString, "§euntil released...", 0, 20 * 2, 0);
                                else
                                    eachPlayer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§8§l[ §" + (finalI % 2 == 0 ? "4" : "f") + "§l! §8§l] §bReleasing seekers in " + timeLeftString));
                            }
                        }
                    }.runTaskLater(WorldEater.getPlugin(), 20L * (secondsToRelease - i)));
                }

                bukkitTasks.add(new BukkitRunnable() {
                    @Override
                    public void run() {
                        for(BukkitTask task : seekerCircleTasks)
                            task.cancel();

                        frozenPlayers.clear();

                        sendGameMessage("§c§lSEEKERS HAVE BEEN RELEASED!");

                        world.setPVP(true);

                        sendSound(Sound.BLOCK_ANVIL_LAND, 2, 2);
                        for(Player eachPlayer : players) {
                            if(!hiders.contains(eachPlayer)) {
                                PlayerState.prepareNormal(eachPlayer, false);

                                eachPlayer.resetTitle();
                                eachPlayer.teleport(getSpawnLocation());
                            } else {
                                eachPlayer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§c§lSEEKERS RELEASED!"));
                            }
                        }

                        for(int i = 0; i < 4; i++)
                            spawnEntityInNaturalHabitat(EntityType.COW);

                        for(int i = 0; i < 3; i++)
                            spawnEntityInNaturalHabitat(EntityType.SHEEP);

                        for(int i = 0; i < 2; i++)
                            spawnEntityInNaturalHabitat(EntityType.CHICKEN);

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

                        timeLeft = 30 * 60;

                        for(int i = timeLeft / 60; i >= 0; i--) {
                            int finalI = i;
                            bukkitTasks.add(new BukkitRunnable() {
                                @Override
                                public void run() {
                                    if(finalI > 0) {
                                        if(finalI % 5 == 0 || finalI < 10)
                                            sendGameMessage("§eThe game has §c" + finalI + "§e minutes remaining.");

                                        switch(finalI) { // Timed events
                                            case 20:
                                                events.add(GameEvent.METEOR_RAIN);

                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_3, 1, 2);
                                                sendGameMessage("§c§lMETEOR RAIN! §cHead to shelter!");

                                                final int meteorAmount = 10;
                                                for(int i = 0; i < meteorAmount; i++) {
                                                    boolean isLast = i == meteorAmount - 1;
                                                    bukkitTasks.add(new BukkitRunnable() {
                                                        @Override
                                                        public void run() {
                                                            Player meteorTarget = getRandomPlayer(true);

                                                            meteorTarget.playSound(meteorTarget, Sound.ITEM_GOAT_HORN_SOUND_0, 1, 0.5f);

                                                            Location targetLocation = meteorTarget.getLocation();

                                                            Random random = new Random();

                                                            Location meteorStart = targetLocation.clone();
                                                            meteorStart.add(random.nextInt(-50, 50), random.nextInt(50, 100), random.nextInt(-50, 50));

                                                            Fireball meteor = world.spawn(meteorStart, Fireball.class);

                                                            meteor.setIsIncendiary(true);
                                                            meteor.setYield(8);

                                                            meteor.setDirection(targetLocation.toVector().subtract(meteorStart.toVector()));

                                                            if(isLast)
                                                                events.remove(GameEvent.METEOR_RAIN);
                                                        }
                                                    }.runTaskLater(WorldEater.getPlugin(), 20 * (i + 1) * 15));
                                                }

                                                break;
                                            case 22:
                                            case 15:
                                            case 8:
                                                events.add(GameEvent.VISIBLE_HIDERS);

                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_7, 1, 0.8f);
                                                sendGameMessage("§c§lALERT! §eHiders are now visible for 10 seconds!");
                                                for(Player hider : hiders) {
                                                    hider.sendTitle("§c§lEXPOSED!", "§eYour location is now visible.", 5, 20 * 10, 5);
                                                    hider.addPotionEffect(
                                                            new PotionEffect(
                                                                    PotionEffectType.GLOWING, 20 * 10, 10, true, false, false
                                                            )
                                                    );
                                                }

                                                bukkitTasks.add(new BukkitRunnable() {
                                                    @Override
                                                    public void run() {
                                                        events.remove(GameEvent.VISIBLE_HIDERS);
                                                    }
                                                }.runTaskLater(WorldEater.getPlugin(), 20 * 10));
                                                break;
                                            case 12:
                                                events.add(GameEvent.DRILLING);

                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_4, 1, 2f);
                                                sendGameMessage("§c§lDRILLING! §cDrills will now be performed randomly. A whole Y-axis will be drilled down into void!");
                                                Random random = new Random();

                                                final int drillHoles = 15;
                                                for(int i = 0; i < drillHoles; i++) {
                                                    boolean isLast = i == drillHoles - 1;

                                                    bukkitTasks.add(new BukkitRunnable() {
                                                        @Override
                                                        public void run() {
                                                            Location drillLocation = new Location(world, random.nextInt(0, 16), 0, random.nextInt(0, 16));
                                                            int yMax = world.getMaxHeight(), yMin = world.getMinHeight();

                                                            for(int y = yMin; y < yMax; y++) {
                                                                Location drillBlock = drillLocation.clone();
                                                                drillBlock.setY(y);

                                                                world.spawnParticle(Particle.SWEEP_ATTACK, drillBlock, 3);
                                                                int finalY = y;
                                                                boolean isLast2 = isLast && y == yMin + 1;

                                                                bukkitTasks.add(new BukkitRunnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        if(finalY % 2 == 0)
                                                                            world.playSound(drillBlock, Sound.BLOCK_BAMBOO_BREAK, 1, 2f);

                                                                        world.spawnParticle(Particle.SWEEP_ATTACK, drillBlock, 5);
                                                                        drillBlock.getBlock().setBlockData(Material.AIR.createBlockData(), false);

                                                                        if(isLast2)
                                                                            events.remove(GameEvent.DRILLING);
                                                                    }
                                                                }.runTaskLater(WorldEater.getPlugin(), 2L * (yMax - y)));
                                                            }
                                                        }
                                                    }.runTaskLater(WorldEater.getPlugin(), 20 * 15 * (i + 1)));
                                                }

                                                break;
                                            case 10:
                                                events.add(GameEvent.SHRINKING_WORLD_BORDER);

                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_6, 1, 2);
                                                sendGameMessage("§eThe world border will shrink in §c30§e seconds!");

                                                bukkitTasks.add(new BukkitRunnable() {
                                                    @Override
                                                    public void run() {
                                                        sendSound(Sound.ITEM_GOAT_HORN_SOUND_6, 1, 0.5f);

                                                        world.getWorldBorder().setSize(32);
                                                        world.getWorldBorder().setWarningTime(20);
                                                        world.getWorldBorder().setCenter(getSpawnLocation());
                                                        sendGameMessage("§eWorld border has shrunk!");

                                                        events.remove(GameEvent.SHRINKING_WORLD_BORDER);
                                                    }
                                                }.runTaskLater(WorldEater.getPlugin(), 20 * 30));
                                                break;
                                            case 5:
                                                events.add(GameEvent.EXPLODING_HORSES);

                                                sendGameMessage("§8<§k-§8> §4§lSUDDEN DEATH! §cExploding horses will appear. They may be killed with a single hit, but - if not - they will put you down.");

                                                sendSound(Sound.ENTITY_HORSE_ANGRY, 5, 5);

                                                final int horseCount = 20;

                                                for(int i = 0; i < horseCount; i++) {
                                                    boolean isLast = i == horseCount - 1;

                                                    bukkitTasks.add(new BukkitRunnable() {
                                                        @Override
                                                        public void run() {
                                                            if(Math.random() * 10 < 3) {
                                                                Player unluckyPlayer = getRandomPlayer();
                                                                unluckyPlayer.playSound(unluckyPlayer, Sound.ENTITY_HORSE_ANGRY, 6, 6);

                                                                Horse horse = (Horse) world.spawnEntity(unluckyPlayer.getLocation(), EntityType.HORSE);

                                                                horse.setVisualFire(true);
                                                                horse.setHealth(0.5);

                                                                bukkitTasks.add(new BukkitRunnable() {
                                                                    @Override
                                                                    public void run() {
                                                                        if(!horse.isDead()) {
                                                                            world.playSound(horse.getLocation(), Sound.ENTITY_GHAST_SCREAM, 1, 0.5f);
                                                                            horse.remove();
                                                                            world.createExplosion(horse.getLocation(), 12);
                                                                        }

                                                                        if(isLast)
                                                                            events.remove(GameEvent.EXPLODING_HORSES);
                                                                    }
                                                                }.runTaskLater(WorldEater.getPlugin(), 20 * 4));
                                                            }
                                                        }
                                                    }.runTaskLater(WorldEater.getPlugin(), 20 * 3 * i));
                                                }
                                                break;
                                            case 1:
                                                sendSound(Sound.ITEM_GOAT_HORN_SOUND_7, 1, 0.5f);
                                                sendGameMessage("§c§lALERT! §eEVERYONE are now visible!");
                                                for(Player eachPlayer : players) {
                                                    eachPlayer.sendTitle("§c§lEXPOSED!", "§eEveryone can now see everyone.", 5, 20 * 5, 5);
                                                    eachPlayer.addPotionEffect(
                                                            new PotionEffect(
                                                                    PotionEffectType.GLOWING, 20 * 60, 10, true, false, false
                                                            )
                                                    );
                                                }
                                                break;
                                        }
                                    } else {
                                        sendGameMessage("§aTime has gone out! Hiders win.");
                                        stop(true);
                                    }
                                }
                            }.runTaskLater(WorldEater.getPlugin(), 20L * 60 * (timeLeft - i)));
                        }

                        bukkitTasks.add(new BukkitRunnable() {
                            @Override
                            public void run() {
                                updateScoreboard();
                                timeLeft--;
                            }
                        }.runTaskTimer(WorldEater.getPlugin(), 40, 20));
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

    private String getBroadcastPrefix() {
        return "§8§l[ §7#" + gameId + " §8§l]§7 ";
    }

    protected void sendBroadcast(World world, String s, String command) {
        WorldEater.sendWorldBroadcast(world, getBroadcastPrefix() + s, command);
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

        if(eventListener != null)
            HandlerList.unregisterAll(eventListener);

        if(scoreboard != null)
            scoreboard.getObjectives().forEach(Objective::unregister);

        if(seekersTeam != null)
            for(String entry : seekersTeam.getEntries())
                seekersTeam.removeEntry(entry);

        if(hidersTeam != null)
            for(String entry : hidersTeam.getEntries())
                hidersTeam.removeEntry(entry);

        new BukkitRunnable() {
            @Override
            public void run() {
                if(world != null) {
                    sendGameMessage("Restoring players...");

                    ArrayList<Player> restorePlayers = new ArrayList<>();
                    restorePlayers.addAll(world.getPlayers());
                    restorePlayers.addAll(players);

                    for(Player player : restorePlayers)
                        if(player.getWorld() == world) {
                            PlayerState.prepareNormal(player, true);
                            player.teleport(WorldEater.getPlugin().getServer().getWorlds().get(0).getSpawnLocation());
                        }

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sendGameMessage("Unloading and discarding world...");

                            WorldEater.getPlugin().getServer().unloadWorld(world, false);

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

        Random random = new Random();
        for(int i = 0; i < 10; i++) {
            double x = random.nextInt(-16, 16), z = random.nextInt(-16, 16);
            FireworkEffect effect = FireworkEffect.builder().flicker(false).trail(false).with(FireworkEffect.Type.STAR).withColor(Color.fromRGB(
                    random.nextInt(256),
                    random.nextInt(256),
                    random.nextInt(256)
            )).build();

            Firework firework = world.spawn(new Location(world, x, world.getHighestBlockYAt((int) x, (int) z) + random.nextInt(2, 10), z), Firework.class);

            FireworkMeta fireworkMeta = firework.getFireworkMeta();
            fireworkMeta.clearEffects();
            fireworkMeta.addEffect(effect);

            firework.setFireworkMeta(fireworkMeta);

            new BukkitRunnable() {
                public void run() {
                    firework.detonate();
                }
            }.runTaskLater(WorldEater.getPlugin(), 20L * random.nextInt(1, 4));
        }
    }

    protected void playerJoin(Player player, boolean spectator) {
        if(spectator) {
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(getSpawnLocation());

            sendSound(Sound.ENTITY_CAT_HISS, 1, 0.5f);

            sendGameMessage("§e" + player.getName() + "§7 is watching as a spectator.");
            WorldEater.sendMessage(player, "Type §e/eatworld leave§7 or click here to stop spectating.", "eatworld leave");

            spectators.add(player);
            return;
        }

        if(status != GameStatus.AWAITING_START) {
            WorldEater.sendMessage(player, "§cThis game has already started. If you wish to spectate this game, use §e/eatworld join " + gameId + " spectate§c or click here.", "eatworld join " + gameId);
            return;
        }

        if(players.size() >= maxPlayers) {
            WorldEater.sendMessage(player, "§cGame is full!");
            return;
        }

        players.add(player);
        sendGameMessage("§a" + player.getName() + "§7 joined the game queue (§6" + players.size() + "§7/§6" + maxPlayers + "§7).");

        WorldEater.sendMessage(player, "§4< §7Quit? §4> §cClick here or type §e/eatworld leave§c to leave the game.", "eatworld leave");

        if(players.size() == maxPlayers)
            sendGameMessage("§aThe game has been filled!");

        sendSound(Sound.ENTITY_AXOLOTL_ATTACK, 1, 2);
    }

    protected void playerLeave(Player player) {
        if(players.contains(player)) {
            players.remove(player);

            if(status == GameStatus.AWAITING_START) {
                sendGameMessage("§cA player in queue left.");
            } else if(status == GameStatus.RUNNING) {
                sendGameMessage("§c" + player.getName() + " quit the game!");

                if(hiders.contains(player)) { // Hider left.
                    hiders.remove(player);

                    if(hiders.isEmpty()) { // No hider remains.
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

            sendGameMessage("Spectator §e" + player.getName() + "§7 left.");
            WorldEater.sendMessage(player, "§cYou stopped spectating the game.");
        }

        player.teleport(WorldEater.getPlugin().getServer().getWorlds().get(0).getSpawnLocation());
    }

    protected Location getCenterTopLocation() {
        return new Location(world, 8, world.getHighestBlockYAt(8, 8), 8);
    }

    protected Location getSpawnLocation() {
        return getSpawnLocation(getCenterTopLocation());
    }

    protected Location getSpawnLocation(Location spawn) {
        while(true)
            if(spawn.getBlock().getType().isAir() || spawn.getBlock().getType().toString().endsWith("_LEAVES") || spawn.getBlock().getType().toString().endsWith("_MUSHROOM"))
                spawn.subtract(0, 1, 0); // Go down 1 block if air or leaf block.
            else if(spawn.getBlock().getType().toString().endsWith("_LOG"))
                spawn.add(1, 0, 0); // Go x++ if wood log block.
            else break;

        return spawn.add(0, 2, 0);
    }

    private Player getRandomPlayer() {
        return getRandomPlayer(false);
    }

    private Player getRandomPlayer(boolean notFrozen) {
        if(!notFrozen)
            return (Player) players.toArray()[(int) (players.size() * Math.random())];

        ArrayList<Player> availablePlayers = new ArrayList<>(players);
        availablePlayers.removeAll(frozenPlayers);

        if(availablePlayers.size() == 0)
            return getRandomPlayer();

        return (Player) availablePlayers.toArray()[(int) (availablePlayers.size() * Math.random())];
    }

    private static boolean hasLiquidOnTop(World world, int x, int z) {
        for(int y = world.getMaxHeight(); y >= world.getMinHeight(); y--)
            if(!world.getBlockAt(x, y, z).getType().isAir()) // Is not air?
                return world.getBlockAt(x, y, z).isLiquid(); // Highest block: liquid or not.
        return false;
    }

    private static boolean isChunkFlooded(Chunk chunk) {
        int floodPoints = 0;

        for(int x = 0; x < 16; x++)
            for(int z = 0; z < 16; z++)
                if(hasLiquidOnTop(chunk.getWorld(), (chunk.getX() << 4) + x, (chunk.getZ() << 4) + z)) {
                    floodPoints++;
                    if(floodPoints > 10)
                        return true;
                }

        return false;
    }

    private void spawnEntityInNaturalHabitat(EntityType type) {
        Location spawn = getSpawnLocation();

        Random random = new Random();

        spawn.setX(random.nextInt(3, 14));
        spawn.setZ(random.nextInt(3, 14));
        spawn.setY(world.getHighestBlockYAt((int) spawn.getX(), (int) spawn.getZ()));

        world.spawnEntity(getSpawnLocation(spawn), type);
    }

    protected void seekerRespawn(Player seeker) {
        Location respawnLocation = seeker.getLastDeathLocation();
        if(respawnLocation != null && respawnLocation.getY() > world.getMinHeight())
            seeker.teleport(seeker.getLastDeathLocation()); // Return seeker to death location.

        PlayerState.prepareIdle(seeker, false);
        frozenPlayers.add(seeker);
        seeker.setGameMode(GameMode.SPECTATOR);

        seeker.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 10, 10, true, false));

        for(int i = 10; i > 0; i--) {
            int finalI = i;
            bukkitTasks.add(new BukkitRunnable() {
                @Override
                public void run() {
                    seeker.sendTitle("§c" + finalI, "§euntil you continue...", 0, 20 * 2, 0);
                }
            }.runTaskLater(WorldEater.getPlugin(), 20L * (10 - i)));
        }

        bukkitTasks.add(new BukkitRunnable() {
            @Override
            public void run() {
                seeker.setGameMode(GameMode.SURVIVAL);
                frozenPlayers.remove(seeker);

                PlayerState.prepareNormal(seeker, false);

                seeker.teleport(getSpawnLocation());
            }
        }.runTaskLater(WorldEater.getPlugin(), 20 * 10));
    }

    private void preparingGameProgressUpdate(PreparationStep step) {
        for(Player eachPlayer : players)
            eachPlayer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§3§lPREPARING GAME §b" + (step.ordinal() + 1) + "§3/§b" + PreparationStep.values().length));
    }

    private static Scoreboard createGameScoreboard() {
        Scoreboard scoreboard = Objects.requireNonNull(Bukkit.getScoreboardManager()).getNewScoreboard();

        Objective objective = scoreboard.registerNewObjective("worldeater_scoreboard", Criteria.DUMMY, "§6§lWORLD§2§lEATER");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        return scoreboard;
    }

    private static void setObjectiveLines(Objective objective, String[] lines) {
        for(String score : Objects.requireNonNull(objective.getScoreboard()).getEntries())
            objective.getScoreboard().resetScores(score);

        int i = 0;
        for(String line : lines)
            objective.getScore(line).setScore(lines.length - i++);
    }

    private void updateScoreboard() {
        setObjectiveLines(scoreboard.getObjectives().toArray(new Objective[0])[0], new String[] {
                "§eTime remaining: " + getFancyTimeLeft(timeLeft),
                "§e" + players.size() + " playing:",
                "§c" + (players.size() - hiders.size()) + "§e seeking",
                "§a" + hiders.size() + "§e hiding",
                "§7---",
                "§7" + spectators.size() + "§8 spectating",
                "§7---",
                events.size() == 0 ? "§7No event" : events.get(0).eventName
        });
    }

    private static String getFancyTimeLeft(int countdown) {
        return (countdown >= 60 ? "§c" + (countdown / 60) + "§em " : "") + "§c" + countdown % 60 + "§es";
    }
}
