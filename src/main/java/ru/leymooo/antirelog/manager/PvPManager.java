package ru.leymooo.antirelog.manager;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.codemc.worldguardwrapper.WorldGuardWrapper;
import org.codemc.worldguardwrapper.region.IWrappedRegion;
import ru.leymooo.antirelog.Antirelog;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.data.PlayerData;
import ru.leymooo.antirelog.event.PvpPreStartEvent;
import ru.leymooo.antirelog.event.PvpPreStartEvent.PvPStatus;
import ru.leymooo.antirelog.event.PvpStartedEvent;
import ru.leymooo.antirelog.event.PvpStoppedEvent;
import ru.leymooo.antirelog.event.PvpTimeUpdateEvent;
import ru.leymooo.antirelog.util.*;

import java.util.*;

public class PvPManager {

    private final Settings settings;
    private final Antirelog plugin;
    @Getter
    private final PowerUpsManager powerUpsManager;
    @Getter
    private final BossbarManager bossbarManager;
    private final Set<String> whiteListedCommands = new HashSet<>();

    public PvPManager(Settings settings, Antirelog plugin) {
        this.settings = settings;
        this.plugin = plugin;
        this.powerUpsManager = new PowerUpsManager(settings);
        this.bossbarManager = new BossbarManager(settings);
        onPluginEnable();
    }

    public void onPluginDisable() {
        this.bossbarManager.clearBossbars();
    }

    public void onPluginEnable() {
        whiteListedCommands.clear();
        if (settings.isDisableCommandsInPvp() && !settings.getWhiteListedCommands().isEmpty()) {
            settings.getWhiteListedCommands().forEach(wcommand -> {
                Command command = CommandMapUtils.getCommand(wcommand);
                whiteListedCommands.add(wcommand.toLowerCase());
                if (command != null) {
                    whiteListedCommands.add(command.getName().toLowerCase());
                    command.getAliases().forEach(alias -> whiteListedCommands.add(alias.toLowerCase()));
                }
            });
        }

        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (PlayerData data : DataManager.getDataList()) {
                Player player = data.getPlayer();
                boolean bypassed = data.isBypassed();
                /* Player is in air (maybe freeze screen if he is in the void for avoid pvp conflicts)
                    And his location delta with last tick is equals zero. */
                boolean isLaggy = !player.isOnGround() && data.getLocation().distanceSquared(player.getLocation()) == 0;

                // Update location in data for check that player does not stuck in air.
                // Update it before calls lag check, because we need update it each tick for see the move delta.
                data.setLocation(player.getLocation());

                // Skip entity if player was laggy and in config we enable lag prevention.
                if (settings.isPreventLags() && isLaggy) {
                    continue;
                }

                int currentTime = getTimeRemainingInPvP(player);
                int timeRemaining = currentTime - 1;
                if (timeRemaining <= 0 || (settings.isDisablePvpInIgnoredRegion() && isInIgnoredRegion(player))) {
                    if (bypassed) {
                        stopPvPSilent(player);
                    } else {
                        stopPvP(player);
                    }
                } else {
                    updatePvpMode(player, bypassed, timeRemaining);
                    callUpdateEvent(player, currentTime, timeRemaining);
                }
            }
        }, 20, 20);

        this.bossbarManager.createBossBars();
    }

    public boolean isInPvP(Player player) {
        PlayerData data = DataManager.get(player);
        return data != null && !data.isBypassed();
    }

    public boolean isInSilentPvP(Player player) {
        PlayerData data = DataManager.get(player);
        return data != null && data.isBypassed();
    }

    public int getTimeRemainingInPvP(Player player) {
        PlayerData data = DataManager.get(player);
        return data == null ? 0 : data.getTime();
    }

    public void playerDamagedByPlayer(Player attacker, Player defender) {
        boolean opponentsAreValid = defender != attacker && attacker != null && defender != null;

        if (opponentsAreValid) {
            boolean opponentsInTheDifferentWorlds = attacker.getWorld() != defender.getWorld();
            boolean opponentsAreDied = defender.isDead() || attacker.isDead();

            if (opponentsInTheDifferentWorlds || opponentsAreDied || EntityUtil.isNPC(attacker, defender)) {
                return;
            }

            tryStartPvP(attacker, defender);
        }
    }

    private void tryStartPvP(Player attacker, Player defender) {
        if (EntityUtil.isInIgnoredWorld(attacker) || isInIgnoredRegion(attacker) || isInIgnoredRegion(defender)) {
            return;
        }

        if (!isPvPModeEnabled() && settings.isDisablePowerups()) {
            if (!EntityUtil.hasBypassPermission(attacker)) {
                powerUpsManager.disablePowerUpsWithRunCommands(attacker);
            }
            if (!EntityUtil.hasBypassPermission(defender)) {
                powerUpsManager.disablePowerUps(defender);
            }
            return;
        }

        if (!isPvPModeEnabled()) {
            return;
        }

        boolean attackerBypassed = EntityUtil.hasBypassPermission(attacker);
        boolean defenderBypassed = EntityUtil.hasBypassPermission(defender);
        if (attackerBypassed && defenderBypassed) {
            return;
        }

        boolean attackerInPvp = isInPvP(attacker) || isInSilentPvP(attacker);
        boolean defenderInPvp = isInPvP(defender) || isInSilentPvP(defender);
        PvPStatus pvpStatus = PvPStatus.ALL_NOT_IN_PVP;

        if (attackerInPvp && defenderInPvp) {
            updateAttackerAndCallEvent(attacker, defender, attackerBypassed);
            updateDefenderAndCallEvent(defender, attacker, defenderBypassed);
            return;
        } else if (attackerInPvp) {
            pvpStatus = PvPStatus.ATTACKER_IN_PVP;
        } else if (defenderInPvp) {
            pvpStatus = PvPStatus.DEFENDER_IN_PVP;
        }

        if (pvpStatus == PvPStatus.ATTACKER_IN_PVP || pvpStatus == PvPStatus.DEFENDER_IN_PVP) {
            if (callPvpPreStartEvent(defender, attacker, pvpStatus)) {
                if (attackerInPvp) {
                    updateAttackerAndCallEvent(attacker, defender, attackerBypassed);
                    startPvp(defender, defenderBypassed, false);
                } else {
                    updateDefenderAndCallEvent(defender, attacker, defenderBypassed);
                    startPvp(attacker, attackerBypassed, true);
                }
                Bukkit.getPluginManager().callEvent(new PvpStartedEvent(defender, attacker, settings.getPvpTime(), pvpStatus));
            }

            return;
        }

        if (callPvpPreStartEvent(defender, attacker, pvpStatus)) {
            startPvp(attacker, attackerBypassed, true);
            startPvp(defender, defenderBypassed, false);
            Bukkit.getPluginManager().callEvent(new PvpStartedEvent(defender, attacker, settings.getPvpTime(), pvpStatus));
        }
    }


    private void startPvp(Player player, boolean bypassed, boolean attacker) {
        if (!bypassed) {
            String message = Utils.color(settings.getMessages().getPvpStarted());

            if (!message.isEmpty()) {
                player.sendMessage(message);
            }

            if (attacker && settings.isDisablePowerups()) {
                powerUpsManager.disablePowerUpsWithRunCommands(player);
            }

            sendTitles(player, true);
        }

        updatePvpMode(player, bypassed, settings.getPvpTime());
        player.setNoDamageTicks(0);
    }

    private void updatePvpMode(Player player, boolean bypassed, int newTime) {
        PlayerData data = DataManager.get(player);

        if (data == null) {
            data = DataManager.add(player, bypassed);
        }

        data.setTime(newTime);

        // If player is not has bypass (he can fight with admin, who dont have combat log, but if he hit the admin,
        // he will have combat log.
        if (!data.isBypassed()) {
            bossbarManager.setBossBar(player, newTime);
            String actionBar = settings.getMessages().getInPvpActionbar();

            if (!actionBar.isEmpty()) {
                sendActionBar(player, Utils.color(Utils.replaceTime(actionBar, newTime)));
            }

            if (settings.isDisablePowerups()) {
                powerUpsManager.disablePowerUps(player);
            }
        }
    }

    private boolean callPvpPreStartEvent(Player defender, Player attacker, PvPStatus pvpStatus) {
        PvpPreStartEvent pvpPreStartEvent = new PvpPreStartEvent(defender, attacker, settings.getPvpTime(), pvpStatus);
        Bukkit.getPluginManager().callEvent(pvpPreStartEvent);
        return !pvpPreStartEvent.isCancelled();
    }

    private void updateAttackerAndCallEvent(Player attacker, Player defender, boolean bypassed) {
        int oldTime = getTimeRemainingInPvP(attacker);
        updatePvpMode(attacker, bypassed, settings.getPvpTime());
        PvpTimeUpdateEvent pvpTimeUpdateEvent = new PvpTimeUpdateEvent(attacker, oldTime, settings.getPvpTime());
        pvpTimeUpdateEvent.setDamagedPlayer(defender);
        Bukkit.getPluginManager().callEvent(pvpTimeUpdateEvent);
    }

    private void updateDefenderAndCallEvent(Player defender, Player attackedBy, boolean bypassed) {
        int oldTime = getTimeRemainingInPvP(defender);
        updatePvpMode(defender, bypassed, settings.getPvpTime());
        PvpTimeUpdateEvent pvpTimeUpdateEvent = new PvpTimeUpdateEvent(defender, oldTime, settings.getPvpTime());
        pvpTimeUpdateEvent.setDamagedBy(attackedBy);
        Bukkit.getPluginManager().callEvent(pvpTimeUpdateEvent);
    }

    private void callUpdateEvent(Player player, int oldTime, int newTime) {
        PvpTimeUpdateEvent pvpTimeUpdateEvent = new PvpTimeUpdateEvent(player, oldTime, newTime);
        Bukkit.getPluginManager().callEvent(pvpTimeUpdateEvent);
    }

    public void stopPvP(Player player) {
        stopPvPSilent(player);
        sendTitles(player, false);
        String message = Utils.color(settings.getMessages().getPvpStopped());
        if (!message.isEmpty()) {
            player.sendMessage(message);
        }
        String actionBar = settings.getMessages().getPvpStoppedActionbar();
        if (!actionBar.isEmpty()) {
            sendActionBar(player, Utils.color(actionBar));
        }
    }

    public void stopPvPSilent(Player player) {
        DataManager.remove(player);
        bossbarManager.clearBossbar(player);
        Bukkit.getPluginManager().callEvent(new PvpStoppedEvent(player));
    }

    public boolean isCommandWhiteListed(String command) {
        if (whiteListedCommands.isEmpty()) {
            return false; //all commands are blocked
        }
        return whiteListedCommands.contains(command.toLowerCase());
    }

    private void sendTitles(Player player, boolean isPvpStarted) {
        String title = isPvpStarted ? settings.getMessages().getPvpStartedTitle() : settings.getMessages().getPvpStoppedTitle();
        String subtitle = isPvpStarted ? settings.getMessages().getPvpStartedSubtitle() : settings.getMessages().getPvpStoppedSubtitle();

        if (title.isEmpty() && subtitle.isEmpty()) {
            return;
        }

        title = "§r" + Utils.color(title);
        subtitle = "§r" + Utils.color(subtitle);

        if (VersionUtils.isVersion(11)) {
            player.sendTitle(title, subtitle, 10, 30, 10);
        } else {
            player.sendTitle(title, subtitle);
        }
    }

    private void sendActionBar(Player player, String message) {
        ActionBar.sendAction(player, message);
    }

    public boolean isPvPModeEnabled() {
        return settings.getPvpTime() > 0;
    }

    public boolean isInIgnoredRegion(Player player) {
        if (!plugin.isWorldGuard() || settings.getIgnoredWgRegions().isEmpty()) {
            return false;
        }

        Set<String> regions = settings.getIgnoredWgRegions();
        Set<IWrappedRegion> wrappedRegions = WorldGuardWrapper.getInstance().getRegions(player.getLocation());
        if (wrappedRegions.isEmpty()) {
            return false;
        }
        for (IWrappedRegion region : wrappedRegions) {
            if (regions.contains(region.getId().toLowerCase())) {
                return true;
            }
        }
        return false;


    }
}
