package pl.msurvival.auth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MSurvivalAuth extends JavaPlugin implements Listener {

    private final Set<UUID> loggedIn = new HashSet<>();
    private final Map<UUID, Integer> titleTasks = new HashMap<>();

    private File usersFile;
    private FileConfiguration users;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadUsers();
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
        getLogger().info("MSurvivalAuth wlaczony!");
    }

    @Override
    public void onDisable() {
        for (Integer taskId : titleTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        titleTasks.clear();
        loggedIn.clear();
    }

    private void registerCommands() {
        if (getCommand("login") != null) {
            getCommand("login").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player)) return true;
                Player player = (Player) sender;

                if (loggedIn.contains(player.getUniqueId()) || isBypassPlayer(player)) {
                    showInfo(player, "&a&lZALOGOWANO", "&7Jesteś już zalogowany.");
                    return true;
                }

                if (!isRegistered(player.getName())) {
                    showRegisterScreen(player);
                    return true;
                }

                if (args.length != 1) {
                    showLoginScreen(player);
                    return true;
                }

                if (checkPassword(player.getName(), args[0])) {
                    loginPlayer(player);
                } else {
                    showInfo(player, "&c&lBŁĘDNE HASŁO", "&7Spróbuj ponownie albo poproś admina o reset.");
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline() && isBlocked(player)) showLoginScreen(player);
                    }, 50L);
                }
                return true;
            });
        }

        if (getCommand("register") != null) {
            getCommand("register").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player)) return true;
                Player player = (Player) sender;

                if (loggedIn.contains(player.getUniqueId()) || isBypassPlayer(player)) {
                    showInfo(player, "&a&lZALOGOWANO", "&7Jesteś już zalogowany.");
                    return true;
                }

                if (isRegistered(player.getName())) {
                    showInfo(player, "&c&lKONTO ISTNIEJE", "&e/login <hasło>");
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline() && isBlocked(player)) showLoginScreen(player);
                    }, 50L);
                    return true;
                }

                if (args.length != 2) {
                    showRegisterScreen(player);
                    return true;
                }

                if (!args[0].equals(args[1])) {
                    showInfo(player, "&c&lBŁĄD", "&7Hasła nie są takie same.");
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline() && isBlocked(player)) showRegisterScreen(player);
                    }, 50L);
                    return true;
                }

                int minLength = getConfig().getInt("settings.min-password-length", 4);
                if (args[0].length() < minLength) {
                    showInfo(player, "&c&lBŁĄD", "&7Hasło jest za krótkie.");
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline() && isBlocked(player)) showRegisterScreen(player);
                    }, 50L);
                    return true;
                }

                registerPlayer(player.getName(), args[0]);
                loginPlayer(player);
                return true;
            });
        }

        if (getCommand("changepassword") != null) {
            getCommand("changepassword").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player)) return true;
                Player player = (Player) sender;

                if (!loggedIn.contains(player.getUniqueId()) && !isBypassPlayer(player)) {
                    showLoginScreen(player);
                    return true;
                }

                if (args.length != 2) {
                    showInfo(player, "&c&lUŻYCIE", "&e/changepassword <stare> <nowe>");
                    return true;
                }

                if (!checkPassword(player.getName(), args[0])) {
                    showInfo(player, "&c&lBŁĄD", "&7Stare hasło jest niepoprawne.");
                    return true;
                }

                registerPlayer(player.getName(), args[1]);
                showInfo(player, "&a&lZMIENIONO HASŁO", "&7Twoje hasło zostało zmienione.");
                return true;
            });
        }

        if (getCommand("authreset") != null) {
            getCommand("authreset").setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("msurvivalauth.admin")) {
                    sender.sendMessage(color("&cBrak uprawnień."));
                    return true;
                }

                if (args.length != 1) {
                    sender.sendMessage(color("&cUżycie: &e/authreset <nick>"));
                    return true;
                }

                String targetName = args[0];
                users.set("users." + normalize(targetName), null);
                saveUsers();

                Player target = Bukkit.getPlayerExact(targetName);
                if (target != null && target.isOnline()) {
                    loggedIn.remove(target.getUniqueId());
                    startAuthTitle(target);
                    showRegisterScreen(target);
                }

                sender.sendMessage(color("&aZresetowano konto gracza &e" + targetName + "&a."));
                return true;
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (isBypassPlayer(player)) {
            loggedIn.add(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> sendAfterLoginWelcome(player), 20L);
            return;
        }

        startAuthTitle(player);

        long timeoutTicks = getConfig().getInt("settings.login-timeout-seconds", 60) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !loggedIn.contains(player.getUniqueId()) && !isBypassPlayer(player)) {
                player.kickPlayer(color(getConfig().getString("messages.login-timeout", "&cCzas na logowanie minął.")));
            }
        }, timeoutTicks);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        loggedIn.remove(player.getUniqueId());
        stopAuthTitle(player);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isBlocked(player)) return;
        if (!getConfig().getBoolean("settings.block-move", true)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ()) {
            event.setTo(from);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isBlocked(player)) return;

        String message = event.getMessage().toLowerCase(Locale.ROOT);
        if (message.startsWith("/login") || message.startsWith("/register")) return;

        event.setCancelled(true);
        if (isRegistered(player.getName())) showLoginScreen(player);
        else showRegisterScreen(player);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isBlocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            if (isBlocked(player)) event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isBlocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (isBlocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (isBlocked(event.getPlayer())) event.setCancelled(true);
    }

    private void loginPlayer(Player player) {
        loggedIn.add(player.getUniqueId());
        stopAuthTitle(player);

        String title = getConfig().getString("success-title.title", "&a&lZALOGOWANO");
        String subtitle = getConfig().getString("success-title.subtitle", "&7Witaj na serwerze!");

        player.sendTitle(
                color(title.replace("%player%", player.getName())),
                color(subtitle.replace("%player%", player.getName())),
                10, 50, 20
        );

        if (getConfig().getBoolean("sounds.success.enabled", true)) {
            try {
                Sound sound = Sound.valueOf(getConfig().getString("sounds.success.name", "ENTITY_PLAYER_LEVELUP"));
                player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
            } catch (Exception ignored) {
            }
        }

        Bukkit.getScheduler().runTaskLater(this, () -> sendAfterLoginWelcome(player), 40L);
    }

    private void sendAfterLoginWelcome(Player player) {
        if (!player.isOnline()) return;

        for (String line : getConfig().getStringList("after-login-welcome")) {
            player.sendMessage(color(line.replace("%player%", player.getName())));
        }
    }

    private void startAuthTitle(Player player) {
        stopAuthTitle(player);

        int taskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || loggedIn.contains(player.getUniqueId()) || isBypassPlayer(player)) {
                stopAuthTitle(player);
                return;
            }

            if (isRegistered(player.getName())) showLoginScreen(player);
            else showRegisterScreen(player);
        }, 0L, getConfig().getInt("login-title.refresh-ticks", 20)).getTaskId();

        titleTasks.put(player.getUniqueId(), taskId);
    }

    private void stopAuthTitle(Player player) {
        Integer taskId = titleTasks.remove(player.getUniqueId());
        if (taskId != null) Bukkit.getScheduler().cancelTask(taskId);
        player.resetTitle();
    }

    private void showLoginScreen(Player player) {
        String title = getConfig().getString("login-title.login-title", "&c&lZALOGUJ SIĘ");
        String subtitle = getConfig().getString("login-title.login-subtitle", "&e/login <hasło>");
        player.sendTitle(color(title.replace("%player%", player.getName())), color(subtitle.replace("%player%", player.getName())), 0, 40, 0);
    }

    private void showRegisterScreen(Player player) {
        String title = getConfig().getString("login-title.register-title", "&6&lZAREJESTRUJ SIĘ");
        String subtitle = getConfig().getString("login-title.register-subtitle", "&e/register <hasło> <powtórz_hasło>");
        player.sendTitle(color(title.replace("%player%", player.getName())), color(subtitle.replace("%player%", player.getName())), 0, 40, 0);
    }

    private void showInfo(Player player, String title, String subtitle) {
        player.sendTitle(color(title.replace("%player%", player.getName())), color(subtitle.replace("%player%", player.getName())), 5, 40, 10);
    }

    private boolean isBlocked(Player player) {
        return !loggedIn.contains(player.getUniqueId()) && !isBypassPlayer(player);
    }

    private boolean isBypassPlayer(Player player) {
        if (!getConfig().getBoolean("admin-bypass.enabled", true)) return false;

        for (String name : getConfig().getStringList("admin-bypass.players")) {
            if (name.equalsIgnoreCase(player.getName())) return true;
        }

        return false;
    }

    private boolean isRegistered(String name) {
        return users.contains("users." + normalize(name));
    }

    private void registerPlayer(String name, String password) {
        String salt = randomSalt();
        String hash = hash(password, salt);
        users.set("users." + normalize(name) + ".salt", salt);
        users.set("users." + normalize(name) + ".hash", hash);
        saveUsers();
    }

    private boolean checkPassword(String name, String password) {
        String path = "users." + normalize(name);
        String salt = users.getString(path + ".salt");
        String savedHash = users.getString(path + ".hash");
        if (salt == null || savedHash == null) return false;
        return savedHash.equals(hash(password, salt));
    }

    private void loadUsers() {
        usersFile = new File(getDataFolder(), "users.yml");

        if (!usersFile.exists()) {
            try {
                getDataFolder().mkdirs();
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

    private String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((password + salt).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
