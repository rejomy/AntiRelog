package ru.leymooo.antirelog.data;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.leymooo.antirelog.util.EntityUtil;

@Getter
@Setter
public class PlayerData {

    private final Player player;
    private final boolean bypassed;
    private Location location;
    private int time;

    public PlayerData(Player player, boolean bypassed) {
        this.player = player;
        this.bypassed = bypassed;
        // Init location as player current location.
        setLocation(player.getLocation());
    }
}
