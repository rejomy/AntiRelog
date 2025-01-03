package ru.leymooo.antirelog.manager;

import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.bukkit.entity.Player;
import ru.leymooo.antirelog.data.PlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Combat data manager class. Here contains player data who particite in fight.
 */
@UtilityClass
public class DataManager {

    @Getter
    private final List<PlayerData> dataList = new CopyOnWriteArrayList<>();

    public PlayerData add(Player player, boolean bypassed) {
        PlayerData data = new PlayerData(player, bypassed);
        dataList.add(data);
        return data;
    }

    public void remove(Player player) {
        dataList.removeIf(data -> data.getPlayer() == player);
    }

    public PlayerData get(Player player) {
        return dataList.stream().filter(data -> data.getPlayer() == player).findAny().orElse(null);
    }

    public boolean contains(Player player) {
        return get(player) != null;
    }

    public void clear() {
        dataList.clear();
    }
}
