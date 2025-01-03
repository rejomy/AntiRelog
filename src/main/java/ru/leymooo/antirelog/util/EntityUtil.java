package ru.leymooo.antirelog.util;

import lombok.experimental.UtilityClass;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ru.leymooo.antirelog.Antirelog;

import java.util.Arrays;

@UtilityClass
public class EntityUtil {

    public boolean isNPC(Entity... entities) {
        return Arrays.stream(entities).anyMatch(entity -> entity.hasMetadata("NPC"));
    }

    public boolean hasBypassPermission(Player player) {
        return player.hasPermission("antirelog.bypass");
    }

    public boolean isInIgnoredWorld(Player player) {
        return Antirelog.INSTANCE.getSettings().getDisabledWorlds().contains(player.getWorld().getName().toLowerCase());
    }

    public boolean hasBypass(Player player) {
        return hasBypassPermission(player) || isInIgnoredWorld(player);
    }
}
