package ru.leymooo.antirelog.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import ru.leymooo.antirelog.Antirelog;

public class ReloadCommand implements CommandExecutor {

    private final Antirelog antirelog;

    public ReloadCommand(Antirelog antirelog) {
        this.antirelog = antirelog;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload") &&
                sender.hasPermission("antirelog.reload")) {
            antirelog.reloadSettings();
            sender.sendMessage("§aReloaded");
            antirelog.getLogger().info(antirelog.getSettings().toString());
        }

        return true;
    }
}
