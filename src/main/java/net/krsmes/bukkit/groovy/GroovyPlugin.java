package net.krsmes.bukkit.groovy;

import groovy.lang.Closure;
import groovy.util.Eval;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.Configuration;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;


public class GroovyPlugin extends JavaPlugin implements Listener {
    static Logger LOG = Logger.getLogger("Minecraft");

    public static final String NAME = "GroovyBukkit";

    public static final String SCRIPT_LOC = "scripts/";
    public static final String STARTUP_LOC = SCRIPT_LOC + "startup/";
    public static final String PLAYER_LOC = SCRIPT_LOC + "players/";
    public static final String SCRIPT_SUFFIX = ".groovy";

    public static final String DATA_TEMP = "temp";
    public static final String DATA_LAST_COMMAND = "lastCommand";
    public static final String DATA_LAST_RESULT = "last";
    public static final String DATA_PERMISSIONS = "permissions";
    public static final String DATA_JOIN_MESSAGE = "joinMessage";
    public static final String GROOVY_GOD = "krsmes";

    public static GroovyPlugin instance;

    boolean enabled;
    boolean registered;
    Map<String, Object> global;
    Map<String, Closure> commands;
    Map<String, GroovyPlayerRunner> playerRunners;
    GroovyRunner runner;

    // from configuration
    List<String> hiddenPlayers;


    public GroovyRunner getRunner() {
        return runner;
    }


    public GroovyRunner getRunner(String playerName) {
        return (playerName == null) ? runner : playerRunners.get(playerName);
    }


    public GroovyRunner getRunner(Player player) {
        if (player == null) {
            return runner;
        }
        GroovyRunner r = playerRunners.get(player.getName());
        if (r != null) {
            r.setPlayer(player);
        }
        return r;
    }


    public Map<String, Object> getData() {
        //noinspection ReturnOfCollectionOrArrayField
        return global;
    }


    public Map<String, Object> getData(Player player) {
        GroovyRunner r = getRunner(player);
        return (r == null) ? null : r.getData();
    }


//
// JavaPlugin
//

    public void onEnable() {
        instance = this;
        try {
            // modify bukkit classes with a few new features
            GroovyBukkitMetaClasses.enable();

            // load global data
            global = loadData();
            global.put(DATA_TEMP, new HashMap());
            commands = new HashMap<String, Closure>();
            playerRunners = new HashMap<String, GroovyPlayerRunner>();

            // setup the global runner (for the console)
            runner = new GroovyRunner(this, global);

            ListenerClosures.enable(this).load(global);
            BlockClosures.enable(this).load(global);
            Events.enable(this).load(global);
            Plots.enable(this).load(global);

            runner._init();

            // register for events
            registerEventHandlers();

            // initialize currently online players
            for (Player p : getServer().getOnlinePlayers()) {
                initializePlayer(p);
            }

            enabled = true;
            LOG.info(getDescription().getName() + ' ' + getDescription().getVersion() + " enabled");
        }
        catch (Exception e) {
            e.printStackTrace();
            LOG.info("Could not enable " + getDescription().getName() + ' ' + getDescription().getVersion());
        }
    }


    public void onDisable() {
        enabled = false;
        try {
            // save all data
            onSave(null);

            Plots.disable();
            Events.disable();
            BlockClosures.disable();
            ListenerClosures.disable();

            // cancel scheduled tasks
            getServer().getScheduler().cancelTasks(instance);

            // shutdown runners for current players
            for (GroovyRunner r : playerRunners.values()) {
                r._shutdown();
            }
            playerRunners.clear();
            playerRunners = null;

            commands.clear();
            commands = null;

            runner._shutdown();
            runner = null;

            global.clear();
            global = null;

            GroovyBukkitMetaClasses.disable();

            instance = null;
            LOG.info(getDescription().getName() + ' ' + getDescription().getVersion() + " disabled");
        }
        catch (Exception e) {
            e.printStackTrace();
            LOG.info("Error disabling " + getDescription().getName() + ' ' + getDescription().getVersion());
        }
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            Player player = (sender instanceof Player) ? (Player) sender : null;
            String cmdName = command.getName();
            if (permitted(player, cmdName)) {
                if ("g".equals(cmdName)) {
                    Object result = command_g(player, args);
                    if (result != null) {
                        sender.sendMessage(Eval.x(result, "x.toString()").toString());
                    }
                    return true;
                }
            }
        }
        catch (Exception e)     {
            LOG.severe(e.getMessage());
        }
        return false;
    }


//
// public methods
//


    @SuppressWarnings({"unchecked"})
    public boolean permitted(Player player, String command) {
        if (enabled) {
            Map<String, List<String>> permissions = (Map) global.get(DATA_PERMISSIONS);
            if (player == null || permissions == null || player.isOp()) {
                return true;
            }
            String name = player.getName();
            if (GROOVY_GOD.equals(name)) {
                return true;
            }
            List<String> globalPermitted = permissions.get("*");
            if (globalPermitted != null && globalPermitted.contains(command)) {
                return true;
            }
            List<String> userPermitted = permissions.get(name);
            // '*' permission does not include 'g'
            return (userPermitted != null && ((!"g".equals(command) && userPermitted.contains("*")) || userPermitted.contains(command)));
        }
        return false;
    }


    public Yaml makeYaml() {
        DumperOptions options = new DumperOptions();
        options.setIndent(4);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        GroovyAPI api = new GroovyAPI(getServer(), getServer().getWorlds().get(0));
        return new Yaml(new GroovyBukkitConstructor(api, getClassLoader()), new GroovyBukkitRepresenter(), options);
    }


    public Object yamlLoad(File f) {
        try {
            return makeYaml().load(new FileReader(f));
        }
        catch (FileNotFoundException e) {
            LOG.severe(e.getMessage() + " : " + f.getPath());
        }
        return null;
    }


    public void yamlDump(File f, Object o) {
        Eval.xyz(f, makeYaml(), o, "if (!x.exists()) x.createNewFile(); x.text = y.dump(z)"); // this would be a dozen+ lines of java
    }


    public Map<String, Object> loadData(String playerName) {
        //noinspection unchecked
        Map<String, Object> result = (Map) yamlLoad(getPlayerDataFile(playerName));
        return (result == null) ? new HashMap<String, Object>() : result;
    }


    public void saveData(String playerName, Map<String, Object> data) {
        yamlDump(getPlayerDataFile(playerName), data);
    }


//
// helper methods
//

    protected void registerEventHandlers() {
        if (!registered) {
            PluginManager mgr = getServer().getPluginManager();
            mgr.registerEvents(this, this);
            registered = true;
        }
    }


    protected void saveData(Map<String, Object> data) {
        yamlDump(getGlobalDataFile(), data);
    }

    protected Map<String, Object> loadData() {
        //noinspection unchecked
        Map<String, Object> result = (Map) yamlLoad(getGlobalDataFile());
        return (result == null) ? new HashMap<String, Object>() : result;
    }

    protected void saveData(GroovyRunner runner) {
        saveData(runner.getPlayer().getName(), savableData(runner.getData()));
    }


    static final List<String> UNSAVABLE = Arrays.asList("temp", "last", "plot");

    protected Map<String, Object> savableData(Map<String, Object> data) {
        Map<String, Object> result = new HashMap<String, Object>();
        if (data != null) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (!UNSAVABLE.contains(e.getKey())) {
                    result.put(e.getKey(), e.getValue());
                }
            }
        }
        return result;
    }


    public File getTempFolder() {
        File temp = new File(getDataFolder(), "tmp/");
        temp.mkdirs();
        return temp;
    }


    protected File getGlobalDataFile() {
        return new File(getDataFolder(), "data.yml");
    }


    protected File getPlayerDataFile(String playerName) {
        return new File(getDataFolder(), "player." + playerName + ".yml");
    }


    protected GroovyPlayerRunner initializePlayer(Player player) {
        String playerName = player.getName();
        Map<String, Object> data = loadData(playerName);
        data.put(DATA_TEMP, new HashMap());
        GroovyPlayerRunner result = new GroovyPlayerRunner(this, player, data);
        result._init();
        playerRunners.put(playerName, result);
        return result;
    }


    protected void finalizePlayer(Player player) {
        GroovyPlayerRunner r = playerRunners.remove(player.getName());
        if (r != null) {
            saveData(r);
            r._shutdown();
        }
    }


    public void onLoad() {
        LOG.info(getDescription().getName() + ' ' + getDescription().getVersion() + " loading");
        Configuration config = getConfig();
        hiddenPlayers = config.getStringList("hidden-players");
    }



    @EventHandler
    protected void onSave(WorldSaveEvent e) {
        LOG.info(getDescription().getName() + ' ' + getDescription().getVersion() + " saving");
        try { saveConfig(); } catch (Exception ex) { LOG.warning("GroovyBukkit onSave() config failed: " + ex.getMessage()); }
        Map<String, Object> data = savableData(global);
        ListenerClosures.instance.save(data);
        BlockClosures.instance.save(data);
        Events.instance.save(data);
        Plots.instance.save(data);
        try { saveData(data); } catch (Exception ex) { LOG.warning("GroovyBukkit onSave() global failed: " + ex.getMessage()); }
        for (GroovyRunner r : playerRunners.values()) {
            try { saveData(r);  } catch (Exception ex) { LOG.warning("GroovyBukkit onSave() " + r.getPlayer() + " failed: " + ex.getMessage()); }
        }
    }


    @EventHandler(priority = EventPriority.LOWEST)
    protected void onLogin(PlayerLoginEvent e) {
        initializePlayer(e.getPlayer());
    }


    @EventHandler(priority = EventPriority.LOW)
    protected void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if (hiddenPlayers.contains(player.getName())) { e.setJoinMessage(null); }
        String joinMessage = (String) global.get(DATA_JOIN_MESSAGE);
        if (joinMessage != null) { player.sendMessage(joinMessage); }
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    protected void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        if (hiddenPlayers.contains(player.getName())) { e.setQuitMessage(null); }
        finalizePlayer(player);
    }


    @EventHandler(priority = EventPriority.LOW)
    protected void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        List<String> cmdsplit = Arrays.asList(e.getMessage().split(" "));
        String cmd = cmdsplit.get(0).substring(1);
        if (commands.containsKey(cmd)) {
            Player player = e.getPlayer();
            List<String> args = new ArrayList<String>(cmdsplit);
            args.remove(0);
            if (permitted(player, cmd)) {
                try {
                    getRunner(player).runCommand(cmd, args);
                }
                catch (Exception ex) {
                    player.sendMessage("ERR: " + ex.getMessage());
                    LOG.severe(ex.getMessage());
                }
            }
            else {
                player.sendMessage("You do not have permission to use /" + cmd);
            }
            e.setCancelled(true);
        }
    }


    protected Object command_g(Player player, String[] args) {
        Object result = null;
        GroovyRunner r = getRunner(player);
        if (r != null) {
            String script = (args != null && args.length > 0) ? Util.join(" ", args) : (String) r.getData().get(DATA_LAST_COMMAND);
            r.getData().put(DATA_LAST_COMMAND, script);
            result = r.runScript(script);
            if (result != null) {
                r.getData().put(DATA_LAST_RESULT, result);
            }
        }
        return result;
    }


}
