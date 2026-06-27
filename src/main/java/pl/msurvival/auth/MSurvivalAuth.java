package pl.msurvival.auth;

import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MSurvivalAuth extends JavaPlugin implements Listener {

    private final Set<UUID> loggedIn = new HashSet<>();
    private File usersFile;
    private FileConfiguration users;
    private final SecureRandom random = new SecureRandom();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadUsers();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MSurvivalAuth wlaczony!");
    }

    @Override
    public void onDisable() {
        saveUsers();
    }

    private void loadUsers() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            try {
                usersFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        users = YamlConfiguration.loadConfiguration(usersFile);
    }

    private void saveUsers() {
        try {
            users.save(usersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        loggedIn.remove(player.getUniqueId());

        boolean registered = isRegistered(player.getName());
        List<String> lines = getConfig().getStringList(registered ? "messages.registered-join" : "messages.not-registered-join");
        for (String line : lines) {
            player.sendMessage(color(line));
        }

        if (getConfig().getBoolean("settings.use-title", true)) {
            if (registered) {
                player.sendTitle(
                        color(getConfig().getString("titles.login-title", "&6&lMSURVIVAL")),
                        color(getConfig().getString("titles.login-subtitle", "&eZaloguj się: /login <hasło>")),
                        10, 80, 20
                );
            } else {
                player.sendTitle(
                        color(getConfig().getString("titles.register-title", "&6&lMSURVIVAL")),
                        color(getConfig().getString("titles.register-subtitle", "&eZarejestruj się: /register <hasło> <hasło>")),
                        10, 80, 20
                );
            }
        }

        int seconds = getConfig().getInt("settings.login-time-seconds", 60);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !isLogged(player)) {
                player.kickPlayer(color(getConfig().getString("messages.kicked-timeout", "&cCzas na logowanie minął.")));
            }
        }, seconds * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loggedIn.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("settings.block-movement", true)) return;
        if (isLogged(event.getPlayer())) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!getConfig().getBoolean("settings.block-chat", true)) return;
        if (isLogged(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(prefix() + color(getConfig().getString("messages.chat-blocked", "&cNajpierw się zaloguj.")));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("settings.block-commands", true)) return;
        Player player = event.getPlayer();
        if (isLogged(player)) return;

        String cmd = event.getMessage().split(" ")[0].replace("/", "").toLowerCase();
        List<String> allowed = getConfig().getStringList("settings.allowed-commands");
        if (!allowed.contains(cmd)) {
            event.setCancelled(true);
            player.sendMessage(prefix() + color(getConfig().getString("messages.command-blocked", "&cNajpierw się zaloguj.")));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (getConfig().getBoolean("settings.block-interactions", true) && !isLogged(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (getConfig().getBoolean("settings.block-interactions", true) && !isLogged(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (getConfig().getBoolean("settings.block-interactions", true) && !isLogged(event.getPlayer())) event.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("authadmin")) {
            return handleAdmin(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(getConfig().getString("messages.only-player", "&cTa komenda jest tylko dla gracza.")));
            return true;
        }

        if (name.equals("register")) {
            return handleRegister(player, args);
        }
        if (name.equals("login")) {
            return handleLogin(player, args);
        }
        if (name.equals("changepassword")) {
            return handleChangePassword(player, args);
        }
        return false;
    }

    private boolean handleRegister(Player player, String[] args) {
        if (isRegistered(player.getName())) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.already-registered", "&cMasz już konto.")));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.register-usage", "&cUżycie: /register <hasło> <powtórz_hasło>")));
            return true;
        }
        if (!args[0].equals(args[1])) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.passwords-not-same", "&cHasła nie są takie same.")));
            errorSound(player);
            return true;
        }
        if (!validPassword(player, args[0])) return true;

        users.set("users." + player.getName().toLowerCase() + ".hash", hashPassword(args[0]));
        users.set("users." + player.getName().toLowerCase() + ".last-name", player.getName());
        saveUsers();
        loggedIn.add(player.getUniqueId());
        player.sendMessage(prefix() + color(getConfig().getString("messages.registered-success", "&aZarejestrowano pomyślnie!")));
        successEffects(player);
        return true;
    }

    private boolean handleLogin(Player player, String[] args) {
        if (isLogged(player)) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.already-logged", "&aJesteś już zalogowany.")));
            return true;
        }
        if (!isRegistered(player.getName())) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.not-registered", "&cNie masz konta.")));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.login-usage", "&cUżycie: /login <hasło>")));
            return true;
        }
        String stored = users.getString("users." + player.getName().toLowerCase() + ".hash");
        if (!checkPassword(args[0], stored)) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.wrong-password", "&cNiepoprawne hasło.")));
            errorSound(player);
            return true;
        }
        loggedIn.add(player.getUniqueId());
        player.sendMessage(prefix() + color(getConfig().getString("messages.login-success", "&aZalogowano pomyślnie.")));
        successEffects(player);
        return true;
    }

    private boolean handleChangePassword(Player player, String[] args) {
        if (!isLogged(player)) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.must-login", "&cNajpierw się zaloguj.")));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.changepassword-usage", "&cUżycie: /changepassword <stare> <nowe>")));
            return true;
        }
        String stored = users.getString("users." + player.getName().toLowerCase() + ".hash");
        if (!checkPassword(args[0], stored)) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.wrong-password", "&cNiepoprawne hasło.")));
            errorSound(player);
            return true;
        }
        if (!validPassword(player, args[1])) return true;
        users.set("users." + player.getName().toLowerCase() + ".hash", hashPassword(args[1]));
        saveUsers();
        player.sendMessage(prefix() + color(getConfig().getString("messages.password-changed", "&aHasło zostało zmienione.")));
        successEffects(player);
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("msurvivalauth.admin")) {
            sender.sendMessage(prefix() + color(getConfig().getString("messages.no-permission", "&cNie masz uprawnień.")));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(prefix() + color(getConfig().getString("messages.reload", "&aPrzeładowano konfigurację.")));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            String nick = args[1].toLowerCase();
            if (!users.contains("users." + nick)) {
                sender.sendMessage(prefix() + color(getConfig().getString("messages.reset-not-found", "&cTen gracz nie ma konta.")));
                return true;
            }
            users.set("users." + nick, null);
            saveUsers();
            sender.sendMessage(prefix() + color(getConfig().getString("messages.reset-success", "&aZresetowano konto gracza &e%player%&a.").replace("%player%", args[1])));
            return true;
        }
        sender.sendMessage(color("&cUżycie: /authadmin reload lub /authadmin reset <nick>"));
        return true;
    }

    private boolean validPassword(Player player, String password) {
        int min = getConfig().getInt("settings.min-password-length", 4);
        int max = getConfig().getInt("settings.max-password-length", 32);
        if (password.length() < min) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.password-too-short", "&cHasło jest za krótkie.").replace("%min%", String.valueOf(min))));
            errorSound(player);
            return false;
        }
        if (password.length() > max) {
            player.sendMessage(prefix() + color(getConfig().getString("messages.password-too-long", "&cHasło jest za długie.").replace("%max%", String.valueOf(max))));
            errorSound(player);
            return false;
        }
        return true;
    }

    private boolean isRegistered(String name) {
        return users.contains("users." + name.toLowerCase() + ".hash");
    }

    private boolean isLogged(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    private String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean checkPassword(String password, String stored) {
        try {
            if (stored == null || !stored.contains(":")) return false;
            String[] parts = stored.split(":");
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] hash = Base64.getDecoder().decode(parts[1]);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] testHash = factory.generateSecret(spec).getEncoded();
            if (hash.length != testHash.length) return false;
            int diff = 0;
            for (int i = 0; i < hash.length; i++) diff |= hash[i] ^ testHash[i];
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void successEffects(Player player) {
        if (getConfig().getBoolean("settings.use-title", true)) {
            player.sendTitle(
                    color(getConfig().getString("titles.success-title", "&a&lZALOGOWANO")),
                    color(getConfig().getString("titles.success-subtitle", "&7Miłej gry!")),
                    10, 50, 15
            );
        }
        if (getConfig().getBoolean("settings.use-sound", true)) playSound(player, getConfig().getString("settings.sound-success", "ENTITY_PLAYER_LEVELUP"));
    }

    private void errorSound(Player player) {
        if (getConfig().getBoolean("settings.use-sound", true)) playSound(player, getConfig().getString("settings.sound-error", "ENTITY_VILLAGER_NO"));
    }

    private void playSound(Player player, String soundName) {
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (Exception ignored) {}
    }

    private String prefix() {
        return color(getConfig().getString("messages.prefix", "&6&lMSurvivalAuth &8» &r"));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
