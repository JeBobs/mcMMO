package com.gmail.nossr50.util.player;

import com.gmail.nossr50.config.Config;
import com.gmail.nossr50.datatypes.player.MMODataSnapshot;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.player.PersistentPlayerData;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.runnables.player.PersistentPlayerDataSaveTask;
import com.gmail.nossr50.runnables.skills.BleedTimerTask;
import com.gmail.nossr50.util.scoreboards.ScoreboardManager;
import com.google.common.collect.ImmutableList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

//TODO: Add per world handling
public final class UserManager {

    private final @NotNull HashSet<McMMOPlayer> playerDataSet; //Used to track players for sync saves on shutdown

    public UserManager() {
        this.playerDataSet = new HashSet<>();
    }

    /**
     * Track a new user.
     *
     * @param mmoPlayer the player profile to start tracking
     */
    public void track(@NotNull McMMOPlayer mmoPlayer) {
        mmoPlayer.getPlayer().setMetadata(mcMMO.playerDataKey, new FixedMetadataValue(mcMMO.p, mmoPlayer));

        playerDataSet.add(mmoPlayer); //for sync saves on shutdown
    }

    /**
     * Cleanup player data
     *
     * @param mmoPlayer target player
     */
    public void cleanupPlayer(@NotNull McMMOPlayer mmoPlayer) {
        playerDataSet.remove(mmoPlayer);
    }

    /**
     * Gets the McMMOPlayer object for a player, this can be null if the player has not yet been loaded.
     * @param player target player
     * @return McMMOPlayer object for this player, null if Player has not been loaded
     */
    public @Nullable McMMOPlayer queryPlayer(@Nullable Player player) {
        if(player == null)
            return null;

        if(player.hasMetadata(mcMMO.playerDataKey))
            return (McMMOPlayer) player.getMetadata(mcMMO.playerDataKey).get(0).value();
        else
            return null;
    }

    public @Nullable PlayerProfile queryPlayer(@NotNull String playerName) {
        return mcMMO.getDatabaseManager().queryPlayerByName(playerName);
    }

    /**
     * Remove a user.
     *
     * @param player The Player object
     */
    public void remove(@NotNull Player player) {
        McMMOPlayer mmoPlayer = queryPlayer(player);

        if(mmoPlayer != null) {
            mmoPlayer.cleanup();
        }

        player.removeMetadata(mcMMO.playerDataKey, mcMMO.p);
        playerDataSet.remove(mmoPlayer); //Clear sync save tracking
    }

    /**
     * Clear all users.
     */
    public void clearAll() {
        for (Player player : mcMMO.p.getServer().getOnlinePlayers()) {
            remove(player);
        }

        playerDataSet.clear(); //Clear sync save tracking
    }

    public @NotNull Collection<McMMOPlayer> getPlayers() {
        Collection<McMMOPlayer> playerCollection = new ArrayList<>();

        for (Player player : mcMMO.p.getServer().getOnlinePlayers()) {
            if (hasPlayerDataKey(player)) {
                playerCollection.add(queryPlayer(player));
            }
        }

        return playerCollection;
    }

    public boolean hasPlayerDataKey(Entity entity) {
        return entity != null && entity.hasMetadata(mcMMO.playerDataKey);
    }

    public @NotNull MMODataSnapshot createPlayerDataSnapshot(@NotNull PersistentPlayerData persistentPlayerData) {
        return new MMODataSnapshot(persistentPlayerData);
    }

    public void saveUserImmediately(@NotNull PersistentPlayerData persistentPlayerData, boolean useSync) {
        if(useSync)
            scheduleSyncSave(createPlayerDataSnapshot(persistentPlayerData)); //Execute sync saves immediately
        else
            scheduleAsyncSaveDelay(createPlayerDataSnapshot(persistentPlayerData), 0);
    }

    public void saveUserWithDelay(@NotNull PersistentPlayerData persistentPlayerData, boolean useSync, int delayTicks) {
        if(useSync)
            scheduleSyncSaveDelay(createPlayerDataSnapshot(persistentPlayerData), delayTicks); //Execute sync saves immediately
        else
            scheduleAsyncSaveDelay(createPlayerDataSnapshot(persistentPlayerData), delayTicks);
    }

    /**
     * Save all users ON THIS THREAD.
     */
    public void saveAllSync() {
        ImmutableList<McMMOPlayer> trackedSyncData = ImmutableList.copyOf(playerDataSet);

        mcMMO.p.getLogger().info("Saving player data... (" + trackedSyncData.size() + ")");

        for (McMMOPlayer onlinePlayer : trackedSyncData) {
            try
            {
                mcMMO.p.getLogger().info("Saving data for player: "+onlinePlayer.getPlayerName());
                saveUserImmediately(onlinePlayer.getPersistentPlayerData(), true);
            }
            catch (Exception e)
            {
                mcMMO.p.getLogger().warning("Could not save mcMMO player data for player: " + onlinePlayer.getPlayerName());
            }
        }

        mcMMO.p.getLogger().info("Finished save operation for "+trackedSyncData.size()+" players!");
    }

    /**
     * This method is called by PlayerQuitEvent to tear down the mmoPlayer.
     * If syncSave is true, then saving is executed immediately
     *
     * @param syncSave if true, data is saved synchronously
     */
    public void logout(@NotNull McMMOPlayer mmoPlayer, boolean syncSave) {
        BleedTimerTask.bleedOut(mmoPlayer.getPlayer());

        //TODO: There is a possibility that async saves don't execute in time if the server is told to shutdown

        if(syncSave) {
            saveUserImmediately(mmoPlayer.getPersistentPlayerData(), true);
        } else {
            saveUserWithDelay(mmoPlayer.getPersistentPlayerData(), false, 20);
        }

        cleanupPlayer(mmoPlayer);

        if(Config.getInstance().getScoreboardsEnabled())
            ScoreboardManager.teardownPlayer(mmoPlayer.getPlayer());

        //Remove user from cache (SQL)
        mcMMO.getDatabaseManager().removeCache(mmoPlayer.getUniqueId());
    }


    public void scheduleAsyncSave(@NotNull MMODataSnapshot mmoDataSnapshot) {
        new PersistentPlayerDataSaveTask(mmoDataSnapshot).runTaskAsynchronously(mcMMO.p);
    }

    public void scheduleSyncSave(@NotNull MMODataSnapshot mmoDataSnapshot) {
        new PersistentPlayerDataSaveTask(mmoDataSnapshot).runTask(mcMMO.p);
    }

    public void scheduleAsyncSaveDelay(@NotNull MMODataSnapshot mmoDataSnapshot, int delayTicks) {
        new PersistentPlayerDataSaveTask(mmoDataSnapshot).runTaskLaterAsynchronously(mcMMO.p, delayTicks);
    }

    public void scheduleSyncSaveDelay(@NotNull MMODataSnapshot mmoDataSnapshot, int delayTicks) {
        new PersistentPlayerDataSaveTask(mmoDataSnapshot).runTaskLater(mcMMO.p, delayTicks);
    }
}
