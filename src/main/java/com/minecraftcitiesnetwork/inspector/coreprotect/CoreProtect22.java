package com.minecraftcitiesnetwork.inspector.coreprotect;

import com.minecraftcitiesnetwork.inspector.InspectorPlugin;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.Database;
import net.coreprotect.database.statement.UserStatement;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class CoreProtect22 implements CoreProtectProvider {

    private static final int RESULTS_PER_PAGE = 7;
    private static final int MAX_LOOKUP_TIME_SECONDS = 31536000; // 1 year
    private static final int SECONDS_PER_DAY = 86400;
    private static final int ACTION_BREAK = 0;
    private static final int ACTION_PLACE = 1;
    private static final int ACTION_INTERACT = 2;

    private final CoreProtectAPI api;

    public CoreProtect22() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        CoreProtectAPI coreProtectAPI = null;
        
        if (plugin != null && plugin instanceof CoreProtect) {
            coreProtectAPI = ((CoreProtect) plugin).getAPI();
            if (coreProtectAPI != null && coreProtectAPI.isEnabled() && coreProtectAPI.APIVersion() >= 10) {
                this.api = coreProtectAPI;
                return;
            }
        }
        
        throw new RuntimeException("CoreProtect API v10+ is required");
    }


    @Override
    public LookupResult performInteractLookup(Player player, Block block, int page) {
        if (api == null) {
            return new LookupResult(Collections.emptyList(), page, 1, false);
        }

        // RIGHT_CLICK on interact blocks: Use blockLookup API which queries exact coordinates
        // Filter to only action 2 (interactions/clicks)
        List<String[]> allResults = api.blockLookup(block, getLookupTimeSeconds());

        if (allResults == null || allResults.isEmpty()) {
            return new LookupResult(Collections.emptyList(), page, 1, false);
        }

        // Filter to only interaction (2) actions
        List<String[]> filteredResults = new ArrayList<>();
        for (String[] result : allResults) {
            try {
                CoreProtectAPI.ParseResult parseResult = api.parseResult(result);
                if (parseResult != null && parseResult.getActionId() == ACTION_INTERACT) {
                    filteredResults.add(result);
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        return buildInteractionLookupResult(filteredResults, block.getLocation(), page);
    }
    
    private LookupResult buildInteractionLookupResult(List<String[]> results, Location location, int page) {
        List<Component> lines = new ArrayList<>();
        
        if (results == null || results.isEmpty()) {
            return new LookupResult(lines, page, 1, false);
        }
        
        int startIndex = (page - 1) * RESULTS_PER_PAGE;
        int endIndex = Math.min(startIndex + RESULTS_PER_PAGE, results.size());
        int totalPages = (int) Math.ceil((double) results.size() / RESULTS_PER_PAGE);
        
        if (startIndex >= results.size()) {
            return new LookupResult(lines, page, totalPages, false);
        }
        
        lines.add(buildHeader("Player Interactions", location));
        
        // Add data lines
        for (int i = startIndex; i < endIndex; i++) {
            String[] result = results.get(i);
            if (result == null || result.length == 0) {
                continue;
            }
            
            try {
                CoreProtectAPI.ParseResult parseResult = api.parseResult(result);
                if (parseResult == null) {
                    continue;
                }
                
                long timeMillis = parseResult.getTimestamp();
                String playerName = parseResult.getPlayer();
                Material type = parseResult.getType();
                String blockName = type != null ? type.name().toLowerCase().replace("_", " ") : "";
                
                Component timeSinceComponent = formatTimeSince(System.currentTimeMillis() - timeMillis);
                Component line = Component.text()
                        .append(timeSinceComponent)
                        .append(Component.text(" "))
                        .append(Component.text("-", NamedTextColor.WHITE))
                        .append(Component.text(" "))
                        .append(Component.text(playerName, NamedTextColor.DARK_AQUA))
                        .append(Component.text(" clicked ", NamedTextColor.WHITE))
                        .append(Component.text(blockName, NamedTextColor.DARK_AQUA))
                        .append(Component.text(".", NamedTextColor.WHITE))
                        .build();
                lines.add(line);
            } catch (Exception e) {
                continue;
            }
        }
        
        return new LookupResult(lines, page, totalPages, !lines.isEmpty());
    }

    @Override
    public LookupResult performBlockLookup(Player player, BlockState blockState, int page) {
        if (api == null) {
            return new LookupResult(Collections.emptyList(), page, 1, false);
        }

        // LEFT_CLICK: Use blockLookup API which queries exact coordinates
        // Filter to only actions 0 (break) and 1 (place)
        Block block = blockState.getLocation().getBlock();
        List<String[]> allResults = api.blockLookup(block, getLookupTimeSeconds());

        if (allResults == null || allResults.isEmpty()) {
            return new LookupResult(Collections.emptyList(), page, 1, false);
        }

        // Filter to only break (0) and place (1) actions
        List<String[]> filteredResults = new ArrayList<>();
        for (String[] result : allResults) {
            try {
                CoreProtectAPI.ParseResult parseResult = api.parseResult(result);
                if (parseResult != null) {
                    int actionId = parseResult.getActionId();
                    if (actionId == ACTION_BREAK || actionId == ACTION_PLACE) {
                        filteredResults.add(result);
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }
        
        return buildBlockLookupResult(filteredResults, blockState.getLocation(), page);
    }

    private int getLookupTimeSeconds() {
        InspectorPlugin plugin = InspectorPlugin.getPlugin();
        if (plugin == null || plugin.getSettings() == null) {
            return MAX_LOOKUP_TIME_SECONDS;
        }

        int configuredDays = plugin.getSettings().historyLimitDate;
        if (configuredDays <= 0 || configuredDays == Integer.MAX_VALUE) {
            return MAX_LOOKUP_TIME_SECONDS;
        }

        long configuredSeconds = (long) configuredDays * SECONDS_PER_DAY;
        return (int) Math.min(MAX_LOOKUP_TIME_SECONDS, configuredSeconds);
    }

    private LookupResult buildBlockLookupResult(List<String[]> results, Location location, int page) {
        List<Component> lines = new ArrayList<>();
        
        if (results == null || results.isEmpty()) {
            return new LookupResult(lines, page, 1, false);
        }
        
        int startIndex = (page - 1) * RESULTS_PER_PAGE;
        int endIndex = Math.min(startIndex + RESULTS_PER_PAGE, results.size());
        int totalPages = (int) Math.ceil((double) results.size() / RESULTS_PER_PAGE);
        
        if (startIndex >= results.size()) {
            return new LookupResult(lines, page, totalPages, false);
        }
        
        lines.add(buildHeader("Inspector", location));
        
        // Add data lines
        for (int i = startIndex; i < endIndex; i++) {
            String[] result = results.get(i);
            if (result == null || result.length == 0) {
                continue;
            }
            
            try {
                CoreProtectAPI.ParseResult parseResult = api.parseResult(result);
                if (parseResult == null) {
                    continue;
                }
                
                long timeMillis = parseResult.getTimestamp();
                String playerName = parseResult.getPlayer();
                int actionId = parseResult.getActionId();
                Material type = parseResult.getType();
                String blockName = type != null ? type.name().toLowerCase().replace("_", " ") : "";
                
                String action = actionId == ACTION_BREAK ? "broke" : "placed";
                NamedTextColor tagColor = actionId == ACTION_PLACE ? NamedTextColor.GREEN : NamedTextColor.RED;
                String tag = actionId == ACTION_PLACE ? "+" : "-";
                Component timeSinceComponent = formatTimeSince(System.currentTimeMillis() - timeMillis);
                
                Component line = Component.text()
                        .append(timeSinceComponent)
                        .append(Component.text(" "))
                        .append(Component.text(tag, tagColor))
                        .append(Component.text(" "))
                        .append(Component.text(playerName, NamedTextColor.DARK_AQUA))
                        .append(Component.text(" " + action + " ", NamedTextColor.WHITE))
                        .append(Component.text(blockName, NamedTextColor.DARK_AQUA))
                        .append(Component.text(".", NamedTextColor.WHITE))
                        .build();
                lines.add(line);
            } catch (Exception e) {
                continue;
            }
        }
        
        return new LookupResult(lines, page, totalPages, !lines.isEmpty());
    }

    @Override
    public LookupResult performChestLookup(Player player, Block block, int page) {
        if (api == null) {
            return new LookupResult(Collections.emptyList(), page, 1, false);
        }

        // Container transactions are stored in a separate 'container' table, not the 'block' table
        // We need to query the container table directly using CoreProtect's Database class
        return performContainerLookup(block.getLocation(), page);
    }

    private LookupResult performContainerLookup(Location location, int page) {
        try {
            return performContainerLookupInternal(location, page);
        } catch (Exception e) {
            InspectorPlugin.log("[DEBUG] Error performing container lookup: " + e.getMessage());
            e.printStackTrace();
            return new LookupResult(Collections.emptyList(), page, 1, false);
        }
    }

    private LookupResult performContainerLookupInternal(Location location, int page) throws Exception {
        // CoreProtect's API doesn't expose container lookups, so we access internal classes directly
        List<Component> lines = new ArrayList<>();
        
        Connection conn = Database.getConnection(true, 1000);
        if (conn == null) {
            return new LookupResult(lines, page, 1, false);
        }

        try (Connection connection = conn; Statement stmt = connection.createStatement()) {
            String prefix = ConfigHandler.prefix;
            String worldName = location.getWorld().getName();
            
            // Get world ID from ConfigHandler cache, or query database if not cached
            Integer worldId = ConfigHandler.worlds.get(worldName);
            if (worldId == null) {
                // Query world table if not in cache
                try (ResultSet worldResult = stmt.executeQuery("SELECT id FROM " + prefix + "world WHERE world = '" + worldName + "' LIMIT 0, 1")) {
                    if (worldResult.next()) {
                        worldId = worldResult.getInt("id");
                    } else {
                        return new LookupResult(lines, page, 1, false);
                    }
                }
            }
            
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            
            int pageStart = (page - 1) * RESULTS_PER_PAGE;
            long timeThreshold = System.currentTimeMillis() / 1000L - getLookupTimeSeconds();

            // Get total count
            int totalCount = 0;
            String countQuery = "SELECT COUNT(*) as count FROM " + prefix + "container " +
                    "WHERE wid = '" + worldId + "' AND x = '" + x + "' AND z = '" + z + "' AND y = '" + y + "' " +
                    "AND time > '" + timeThreshold + "' LIMIT 0, 1";
            try (ResultSet countResults = stmt.executeQuery(countQuery)) {
                if (countResults.next()) {
                    totalCount = countResults.getInt("count");
                }
            }
            int totalPages = (int) Math.ceil(totalCount / (RESULTS_PER_PAGE + 0.0));
            
            // Query data
            String query = "SELECT time,user,action,type,data,amount FROM " + prefix + "container " +
                    "WHERE wid = '" + worldId + "' AND x = '" + x + "' AND z = '" + z + "' AND y = '" + y + "' " +
                    "AND time > '" + timeThreshold + "' " +
                    "ORDER BY rowid DESC LIMIT " + pageStart + ", " + RESULTS_PER_PAGE;
            
            lines.add(buildHeader("Container Transactions", location));
            
            boolean hasAnyData = false;
            
            try (ResultSet results = stmt.executeQuery(query)) {
                while (results.next()) {
                    long resultTime = results.getLong("time");
                    int resultUserId = results.getInt("user");
                    int resultAction = results.getInt("action");
                    int resultType = results.getInt("type");
                    int resultAmount = results.getInt("amount");
                    
                    // Load player name
                    UserStatement.loadName(connection, resultUserId);
                    String playerName = ConfigHandler.playerIdCacheReversed.get(resultUserId);
                    if (playerName == null) {
                        playerName = "Unknown";
                    }
                    
                    // Get material from ConfigHandler cache, or query database if not cached
                    Material material = null;
                    String materialNameFromCache = ConfigHandler.materialsReversed.get(resultType);
                    if (materialNameFromCache != null) {
                        // Parse material name (format: minecraft:material_name)
                        String materialKey = materialNameFromCache.toUpperCase();
                        if (materialKey.contains(":")) {
                            materialKey = materialKey.split(":")[1];
                        }
                        material = Material.getMaterial(materialKey);
                    }
                    if (material == null) {
                        material = Material.AIR;
                    }
                    
                    String materialName = material.name().toLowerCase().replace("_", " ");
                    String action = resultAction != 0 ? "added" : "removed";
                    NamedTextColor tagColor = resultAction != 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
                    String tag = resultAction != 0 ? "+" : "-";
                    
                    long timeMillis = resultTime * 1000L;
                    Component timeSinceComponent = formatTimeSince(System.currentTimeMillis() - timeMillis);
                    
                    Component line = Component.text()
                            .append(timeSinceComponent)
                            .append(Component.text(" "))
                            .append(Component.text(tag, tagColor))
                            .append(Component.text(" "))
                            .append(Component.text(playerName, NamedTextColor.DARK_AQUA))
                            .append(Component.text(" " + action + " ", NamedTextColor.WHITE))
                            .append(Component.text("x" + resultAmount + " " + materialName, NamedTextColor.DARK_AQUA))
                            .append(Component.text(".", NamedTextColor.WHITE))
                            .build();
                    lines.add(line);
                    hasAnyData = true;
                }
            }
            
            if (!hasAnyData) {
                lines.clear();
            }
            
            return new LookupResult(lines, page, totalPages, hasAnyData);
        }
    }

    private Component buildHeader(String title, Location location) {
        return Component.text()
                .append(Component.text("----- ", NamedTextColor.WHITE))
                .append(Component.text(title, NamedTextColor.DARK_AQUA))
                .append(Component.text(" ----- ", NamedTextColor.WHITE))
                .append(Component.text("(x" + location.getBlockX() + "/y" + location.getBlockY() + 
                        "/z" + location.getBlockZ() + ")", NamedTextColor.GRAY))
                .build();
    }

    private Component formatTimeSince(long milliseconds) {
        // Match CoreProtect's time formatting (using seconds, not milliseconds)
        long seconds = milliseconds / 1000;
        double timeSince = seconds / 60.0; // Convert to minutes
        
        DecimalFormat decimalFormat = new DecimalFormat("0.00");
        String timeStr;
        
        // Minutes
        if (timeSince < 60.0) {
            timeStr = decimalFormat.format(timeSince) + "m ago";
        }
        // Hours
        else if ((timeSince = timeSince / 60.0) < 24.0) {
            timeStr = decimalFormat.format(timeSince) + "h ago";
        }
        // Days
        else {
            timeSince = timeSince / 24.0;
            timeStr = decimalFormat.format(timeSince) + "d ago";
        }
        
        // CoreProtect wraps time in GREY color (matching ChatUtils.getTimeSince with component=true)
        return Component.text(timeStr, NamedTextColor.GRAY);
    }

}
