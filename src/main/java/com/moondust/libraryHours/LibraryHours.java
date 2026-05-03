package com.moondust.libraryHours;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LibraryHours extends JavaPlugin implements TabExecutor, Listener {

    private static final String COMMAND_NAME = "library";
    private static final String ADMIN_PERMISSION = "libraryhours.admin";
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("(?i)(?:&?#)([0-9a-f]{6})");
    private static final String VENTURE_CHAT_PLUGIN_NAME = "VentureChat";
    private static final String VENTURE_CHAT_EVENT_CLASS = "mineverse.Aust1n46.chat.api.events.VentureChatEvent";

    private final Map<String, LibraryRegion> libraryRegions = new HashMap<>();
    private final Map<UUID, Long> coinBalances = new HashMap<>();
    private final Map<UUID, Location> pos1Selections = new HashMap<>();
    private final Map<UUID, Location> pos2Selections = new HashMap<>();
    private final Set<UUID> playersInLibrary = new HashSet<>();

    private FileConfiguration langConfig;
    private File playerDataFile;
    private FileConfiguration playerDataConfig;
    private boolean ventureChatHookRegistered;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("lang.yml", false);
        loadLangConfig();
        initializePlayerData();
        migrateLegacyPlayerData();
        migrateLegacyRegionConfig();
        loadRegions();
        loadCoinBalances();
        initializePlayerRegionState();

        if (getCommand(COMMAND_NAME) != null) {
            getCommand(COMMAND_NAME).setExecutor(this);
            getCommand(COMMAND_NAME).setTabCompleter(this);
        }

        getServer().getPluginManager().registerEvents(this, this);
        registerVentureChatHookIfAvailable();
        startPassiveRewardTask();
    }

    @Override
    public void onDisable() {
        playersInLibrary.clear();
        pos1Selections.clear();
        pos2Selections.clear();
        saveCoinBalances();
    }

    private void loadLangConfig() {
        File langFile = new File(getDataFolder(), "lang.yml");
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    private void initializePlayerData() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        if (!playerDataFile.exists()) {
            try {
                playerDataFile.createNewFile();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to create playerdata.yml", exception);
            }
        }

        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void migrateLegacyPlayerData() {
        ConfigurationSection legacySection = getConfig().getConfigurationSection("player-coins");
        ConfigurationSection existingSection = playerDataConfig.getConfigurationSection("player-coins");
        boolean hasExistingPlayerData = existingSection != null && !existingSection.getKeys(false).isEmpty();
        if (legacySection == null || hasExistingPlayerData) {
            return;
        }

        playerDataConfig.set("player-coins", null);
        for (String key : legacySection.getKeys(false)) {
            playerDataConfig.set("player-coins." + key, legacySection.getLong(key));
        }

        try {
            playerDataConfig.save(playerDataFile);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to migrate legacy player data", exception);
        }

        getConfig().set("player-coins", null);
        saveConfig();
    }

    private void migrateLegacyRegionConfig() {
        ConfigurationSection legacyRegion = getConfig().getConfigurationSection("library.region");
        ConfigurationSection regionsSection = getConfig().getConfigurationSection("library.regions");
        boolean hasSavedRegions = regionsSection != null && !regionsSection.getKeys(false).isEmpty();
        if (legacyRegion == null || hasSavedRegions) {
            return;
        }

        String pos1World = legacyRegion.getString("pos1.world", "");
        String pos2World = legacyRegion.getString("pos2.world", "");
        if (pos1World.isBlank() || pos2World.isBlank()) {
            return;
        }

        getConfig().set("library.regions.default.world", pos1World);
        getConfig().set("library.regions.default.pos1.x", legacyRegion.getInt("pos1.x"));
        getConfig().set("library.regions.default.pos1.y", legacyRegion.getInt("pos1.y"));
        getConfig().set("library.regions.default.pos1.z", legacyRegion.getInt("pos1.z"));
        getConfig().set("library.regions.default.pos2.x", legacyRegion.getInt("pos2.x"));
        getConfig().set("library.regions.default.pos2.y", legacyRegion.getInt("pos2.y"));
        getConfig().set("library.regions.default.pos2.z", legacyRegion.getInt("pos2.z"));
        getConfig().set("library.region", null);
        saveConfig();
    }

    private void loadRegions() {
        libraryRegions.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("library.regions");
        if (section == null) {
            return;
        }

        for (String regionName : section.getKeys(false)) {
            String basePath = "library.regions." + regionName;
            String worldName = getConfig().getString(basePath + ".world", "");
            if (worldName.isBlank()) {
                continue;
            }

            LibraryRegion region = new LibraryRegion(
                    regionName,
                    worldName,
                    Math.min(getConfig().getInt(basePath + ".pos1.x"), getConfig().getInt(basePath + ".pos2.x")),
                    Math.max(getConfig().getInt(basePath + ".pos1.x"), getConfig().getInt(basePath + ".pos2.x")),
                    Math.min(getConfig().getInt(basePath + ".pos1.y"), getConfig().getInt(basePath + ".pos2.y")),
                    Math.max(getConfig().getInt(basePath + ".pos1.y"), getConfig().getInt(basePath + ".pos2.y")),
                    Math.min(getConfig().getInt(basePath + ".pos1.z"), getConfig().getInt(basePath + ".pos2.z")),
                    Math.max(getConfig().getInt(basePath + ".pos1.z"), getConfig().getInt(basePath + ".pos2.z"))
            );
            libraryRegions.put(regionName, region);
        }
    }

    private void saveRegion(LibraryRegion region) {
        String basePath = "library.regions." + region.name();
        getConfig().set(basePath + ".world", region.worldName());
        getConfig().set(basePath + ".pos1.x", region.minX());
        getConfig().set(basePath + ".pos1.y", region.minY());
        getConfig().set(basePath + ".pos1.z", region.minZ());
        getConfig().set(basePath + ".pos2.x", region.maxX());
        getConfig().set(basePath + ".pos2.y", region.maxY());
        getConfig().set(basePath + ".pos2.z", region.maxZ());
        saveConfig();
    }

    private void removeRegion(String regionName) {
        libraryRegions.remove(regionName);
        getConfig().set("library.regions." + regionName, null);
        saveConfig();
    }

    private void initializePlayerRegionState() {
        playersInLibrary.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInsideAnyLibraryRegion(player.getLocation())) {
                playersInLibrary.add(player.getUniqueId());
            }
        }
    }

    private void startPassiveRewardTask() {
        long intervalTicks = Math.max(20L, getConfig().getLong("rewards.interval-seconds", 60L) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::rewardPlayersInLibrary, intervalTicks, intervalTicks);
    }

    private void rewardPlayersInLibrary() {
        if (libraryRegions.isEmpty()) {
            return;
        }

        int expReward = Math.max(0, getConfig().getInt("rewards.exp", 5));
        long coinReward = Math.max(0L, getConfig().getLong("rewards.coins", 10L));
        boolean changed = false;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isInsideAnyLibraryRegion(player.getLocation())) {
                continue;
            }

            if (expReward > 0) {
                player.giveExp(expReward);
            }

            if (coinReward > 0) {
                UUID uuid = player.getUniqueId();
                coinBalances.put(uuid, coinBalances.getOrDefault(uuid, 0L) + coinReward);
                changed = true;
            }

            sendRewardActionBar(player, coinReward, expReward);
        }

        if (changed) {
            saveCoinBalances();
        }
    }

    private boolean isInsideAnyLibraryRegion(Location location) {
        return !getRegionNamesAt(location).isEmpty();
    }

    private Set<String> getRegionNamesAt(Location location) {
        Set<String> regionsAtLocation = new HashSet<>();
        for (LibraryRegion region : libraryRegions.values()) {
            if (region.contains(location)) {
                regionsAtLocation.add(region.name());
            }
        }
        return regionsAtLocation;
    }

    private void setSelectionPosition(UUID playerId, String positionKey, Location location) {
        Location copiedLocation = new Location(
                location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );

        if ("pos1".equals(positionKey)) {
            pos1Selections.put(playerId, copiedLocation);
            return;
        }

        pos2Selections.put(playerId, copiedLocation);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Set<String> senderRegions = getRegionNamesAt(event.getPlayer().getLocation());
        if (senderRegions.isEmpty()) {
            return;
        }

        filterRecipientsBySharedLibraryRegion(senderRegions, event.getRecipients());
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (VENTURE_CHAT_PLUGIN_NAME.equalsIgnoreCase(event.getPlugin().getName())) {
            registerVentureChatHookIfAvailable();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isInsideAnyLibraryRegion(player.getLocation())) {
            playersInLibrary.add(player.getUniqueId());
            sendConfiguredMessage(player, "messages.enter");
            return;
        }

        playersInLibrary.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playersInLibrary.remove(playerId);
        pos1Selections.remove(playerId);
        pos2Selections.remove(playerId);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || sameBlock(event.getFrom(), to)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        boolean wasInside = playersInLibrary.contains(playerId);
        boolean isInside = isInsideAnyLibraryRegion(to);

        if (wasInside == isInside) {
            return;
        }

        if (isInside) {
            playersInLibrary.add(playerId);
            sendConfiguredMessage(event.getPlayer(), "messages.enter");
            return;
        }

        playersInLibrary.remove(playerId);
        sendConfiguredMessage(event.getPlayer(), "messages.leave");
    }

    private boolean sameBlock(Location from, Location to) {
        return from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }

    private void filterRecipientsBySharedLibraryRegion(Set<String> senderRegions, Set<Player> recipients) {
        recipients.removeIf(recipient -> {
            Set<String> recipientRegions = getRegionNamesAt(recipient.getLocation());
            recipientRegions.retainAll(senderRegions);
            return recipientRegions.isEmpty();
        });
    }

    private void registerVentureChatHookIfAvailable() {
        if (ventureChatHookRegistered) {
            return;
        }

        Plugin ventureChatPlugin = getServer().getPluginManager().getPlugin(VENTURE_CHAT_PLUGIN_NAME);
        if (ventureChatPlugin == null || !ventureChatPlugin.isEnabled()) {
            return;
        }

        try {
            Class<?> rawEventClass = ventureChatPlugin.getClass().getClassLoader().loadClass(VENTURE_CHAT_EVENT_CLASS);
            if (!org.bukkit.event.Event.class.isAssignableFrom(rawEventClass)) {
                getLogger().warning("VentureChat hook skipped: event class is not a Bukkit event.");
                return;
            }

            @SuppressWarnings("unchecked")
            Class<? extends org.bukkit.event.Event> eventClass = (Class<? extends org.bukkit.event.Event>) rawEventClass;
            EventExecutor executor = (listener, event) -> handleVentureChatEvent(event);
            getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.HIGHEST, executor, this, true);
            ventureChatHookRegistered = true;
            getLogger().info("Hooked VentureChat recipient filtering for quiet regions.");
        } catch (ClassNotFoundException exception) {
            getLogger().warning("VentureChat hook skipped: VentureChatEvent class was not found.");
        }
    }

    private void handleVentureChatEvent(org.bukkit.event.Event event) {
        try {
            Object usernameValue = event.getClass().getMethod("getUsername").invoke(event);
            if (!(usernameValue instanceof String senderName)) {
                return;
            }

            Player sender = Bukkit.getPlayerExact(senderName);
            if (sender == null) {
                return;
            }

            Set<String> senderRegions = getRegionNamesAt(sender.getLocation());
            if (senderRegions.isEmpty()) {
                return;
            }

            Object recipientsValue = event.getClass().getMethod("getRecipients").invoke(event);
            if (!(recipientsValue instanceof Set<?> rawRecipients)) {
                return;
            }

            List<Player> recipients = new ArrayList<>();
            for (Object recipient : rawRecipients) {
                if (recipient instanceof Player player) {
                    recipients.add(player);
                }
            }

            for (Player recipient : recipients) {
                Set<String> recipientRegions = getRegionNamesAt(recipient.getLocation());
                recipientRegions.retainAll(senderRegions);
                if (recipientRegions.isEmpty()) {
                    rawRecipients.remove(recipient);
                }
            }
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("VentureChat hook failed while filtering recipients: " + exception.getMessage());
        }
    }

    private void sendConfiguredMessage(Player player, String path) {
        String message = getLang(path);
        if (message.isBlank()) {
            return;
        }

        player.sendMessage(message);
    }

    private void sendRewardActionBar(Player player, long coinReward, int expReward) {
        List<String> parts = new ArrayList<>();

        if (coinReward > 0) {
            parts.add(formatConfiguredText("messages.action-bar.coins", coinReward));
        }
        if (expReward > 0) {
            parts.add(formatConfiguredText("messages.action-bar.exp", expReward));
        }
        if (parts.isEmpty()) {
            return;
        }

        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(String.join(" ", parts)));
    }

    private String formatConfiguredText(String path, long amount) {
        return getLang(path, Map.of("%amount%", Long.toString(amount)));
    }

    private String getLang(String path) {
        return getLang(path, Map.of());
    }

    private String getLang(String path, Map<String, String> replacements) {
        String message = langConfig.getString(path, "");
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        return colorize(message);
    }

    private void sendMessage(CommandSender sender, String path) {
        sendMessage(sender, path, Map.of());
    }

    private void sendMessage(CommandSender sender, String path, Map<String, String> replacements) {
        String message = getLang(path, replacements);
        if (!message.isBlank()) {
            sender.sendMessage(message);
        }
    }

    private String colorize(String input) {
        String translated = translateHexColors(input);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', translated);
    }

    private String translateHexColors(String input) {
        Matcher matcher = HEX_COLOR_PATTERN.matcher(input);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String hexColor = "#" + matcher.group(1);
            matcher.appendReplacement(output, Matcher.quoteReplacement(net.md_5.bungee.api.ChatColor.of(hexColor).toString()));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private void saveCoinBalances() {
        playerDataConfig.set("player-coins", null);
        for (Map.Entry<UUID, Long> entry : coinBalances.entrySet()) {
            playerDataConfig.set("player-coins." + entry.getKey(), entry.getValue());
        }

        try {
            playerDataConfig.save(playerDataFile);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to save playerdata.yml", exception);
        }
    }

    private void loadCoinBalances() {
        coinBalances.clear();
        ConfigurationSection section = playerDataConfig.getConfigurationSection("player-coins");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                coinBalances.put(UUID.fromString(key), section.getLong(key));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping invalid player coin entry: " + key);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase(COMMAND_NAME)) {
            return false;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subCommand) {
            case "pos1" -> handleSetPosition(sender, "pos1");
            case "pos2" -> handleSetPosition(sender, "pos2");
            case "save" -> handleSaveRegion(sender, args);
            case "remove" -> handleRemoveRegion(sender, args);
            case "list" -> handleListRegions(sender);
            case "info" -> handleInfo(sender, args);
            case "reload" -> handleReload(sender);
            case "balance" -> handleBalance(sender);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean handleSetPosition(CommandSender sender, String positionKey) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "commands.players-only.positions");
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sendMessage(sender, "commands.no-permission");
            return true;
        }

        setSelectionPosition(player.getUniqueId(), positionKey, player.getLocation());
        sendMessage(sender, "commands.positions.set", Map.of(
                "%position%", positionKey,
                "%x%", Integer.toString(player.getLocation().getBlockX()),
                "%y%", Integer.toString(player.getLocation().getBlockY()),
                "%z%", Integer.toString(player.getLocation().getBlockZ()),
                "%world%", player.getWorld().getName()
        ));
        return true;
    }

    private boolean handleSaveRegion(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "commands.players-only.save");
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sendMessage(sender, "commands.no-permission");
            return true;
        }
        if (args.length < 2) {
            sendMessage(sender, "commands.usage.save");
            return true;
        }

        String regionName = normalizeRegionName(args[1]);
        if (regionName == null) {
            sendMessage(sender, "commands.region.invalid-name");
            return true;
        }

        Location pos1 = pos1Selections.get(player.getUniqueId());
        Location pos2 = pos2Selections.get(player.getUniqueId());
        if (pos1 == null || pos2 == null) {
            sendMessage(sender, "commands.region.selection-missing");
            return true;
        }
        if (!pos1.getWorld().equals(pos2.getWorld())) {
            sendMessage(sender, "commands.region.selection-world-mismatch");
            return true;
        }

        boolean alreadyExists = libraryRegions.containsKey(regionName);
        LibraryRegion region = LibraryRegion.fromLocations(regionName, pos1, pos2);
        libraryRegions.put(regionName, region);
        saveRegion(region);
        initializePlayerRegionState();

        sendMessage(sender, alreadyExists ? "commands.region.updated" : "commands.region.saved", Map.of("%name%", regionName));
        return true;
    }

    private boolean handleRemoveRegion(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sendMessage(sender, "commands.no-permission");
            return true;
        }
        if (args.length < 2) {
            sendMessage(sender, "commands.usage.remove");
            return true;
        }

        String regionName = args[1].toLowerCase(Locale.ROOT);
        if (!libraryRegions.containsKey(regionName)) {
            sendMessage(sender, "commands.region.not-found", Map.of("%name%", regionName));
            return true;
        }

        removeRegion(regionName);
        initializePlayerRegionState();
        sendMessage(sender, "commands.region.removed", Map.of("%name%", regionName));
        return true;
    }

    private boolean handleListRegions(CommandSender sender) {
        if (libraryRegions.isEmpty()) {
            sendMessage(sender, "commands.region.none-configured");
            return true;
        }

        sendMessage(sender, "commands.region.list", Map.of("%regions%", String.join(", ", new TreeSet<>(libraryRegions.keySet()))));
        return true;
    }

    private boolean handleBalance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "commands.players-only.balance");
            return true;
        }

        long balance = coinBalances.getOrDefault(player.getUniqueId(), 0L);
        sendMessage(sender, "commands.balance", Map.of("%amount%", Long.toString(balance)));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sendMessage(sender, "commands.no-permission");
            return true;
        }

        saveCoinBalances();
        reloadConfig();
        loadLangConfig();
        initializePlayerData();
        migrateLegacyPlayerData();
        migrateLegacyRegionConfig();
        loadRegions();
        loadCoinBalances();
        initializePlayerRegionState();
        sendMessage(sender, "commands.reload.success");
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String regionName = args[1].toLowerCase(Locale.ROOT);
            LibraryRegion region = libraryRegions.get(regionName);
            if (region == null) {
                sendMessage(sender, "commands.region.not-found", Map.of("%name%", regionName));
                return true;
            }

            sendMessage(sender, "commands.region.info.detail", Map.of(
                    "%name%", region.name(),
                    "%world%", region.worldName(),
                    "%min_x%", Integer.toString(region.minX()),
                    "%min_y%", Integer.toString(region.minY()),
                    "%min_z%", Integer.toString(region.minZ()),
                    "%max_x%", Integer.toString(region.maxX()),
                    "%max_y%", Integer.toString(region.maxY()),
                    "%max_z%", Integer.toString(region.maxZ())
            ));
        } else {
            sendMessage(sender, "commands.region.info.count", Map.of("%count%", Integer.toString(libraryRegions.size())));
            if (!libraryRegions.isEmpty()) {
                sendMessage(sender, "commands.region.info.names", Map.of("%regions%", String.join(", ", new TreeSet<>(libraryRegions.keySet()))));
            }
        }

        sendMessage(sender, "commands.region.info.rewards", Map.of(
                "%seconds%", Long.toString(Math.max(1L, getConfig().getLong("rewards.interval-seconds", 60L))),
                "%exp%", Integer.toString(Math.max(0, getConfig().getInt("rewards.exp", 5))),
                "%coins%", Long.toString(Math.max(0L, getConfig().getLong("rewards.coins", 10L)))
        ));
        return true;
    }

    private String normalizeRegionName(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_-]+") ? normalized : null;
    }

    private void sendUsage(CommandSender sender) {
        sendMessage(sender, "commands.usage.header");
        sendMessage(sender, "commands.usage.pos1");
        sendMessage(sender, "commands.usage.pos2");
        sendMessage(sender, "commands.usage.save");
        sendMessage(sender, "commands.usage.remove");
        sendMessage(sender, "commands.usage.list");
        sendMessage(sender, "commands.usage.info");
        sendMessage(sender, "commands.usage.reload");
        sendMessage(sender, "commands.usage.balance");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase(COMMAND_NAME)) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("pos1");
            suggestions.add("pos2");
            suggestions.add("save");
            suggestions.add("remove");
            suggestions.add("list");
            suggestions.add("info");
            suggestions.add("reload");
            suggestions.add("balance");
            return suggestions;
        }

        if (args.length == 2) {
            String subCommand = args[0].toLowerCase(Locale.ROOT);
            if ("remove".equals(subCommand) || "info".equals(subCommand)) {
                return new ArrayList<>(new TreeSet<>(libraryRegions.keySet()));
            }
        }

        return List.of();
    }

    private record LibraryRegion(
            String name,
            String worldName,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) {
        private static LibraryRegion fromLocations(String name, Location pos1, Location pos2) {
            return new LibraryRegion(
                    name,
                    pos1.getWorld().getName(),
                    Math.min(pos1.getBlockX(), pos2.getBlockX()),
                    Math.max(pos1.getBlockX(), pos2.getBlockX()),
                    Math.min(pos1.getBlockY(), pos2.getBlockY()),
                    Math.max(pos1.getBlockY(), pos2.getBlockY()),
                    Math.min(pos1.getBlockZ(), pos2.getBlockZ()),
                    Math.max(pos1.getBlockZ(), pos2.getBlockZ())
            );
        }

        private boolean contains(Location location) {
            return location.getWorld().getName().equals(worldName)
                    && location.getBlockX() >= minX
                    && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY
                    && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ
                    && location.getBlockZ() <= maxZ;
        }
    }
}
