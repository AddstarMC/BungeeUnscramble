package au.com.addstar.unscramble;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import au.com.addstar.unscramble.config.GameConfig;
import au.com.addstar.unscramble.config.MainConfig;
import au.com.addstar.unscramble.config.UnclaimedPrizes;
import au.com.addstar.unscramble.prizes.PointsPrize;
import au.com.addstar.unscramble.prizes.Prize;
import au.com.addstar.unscramble.prizes.SavedPrize;

import net.cubespace.Yamler.Config.InvalidConfigurationException;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Unscramble extends Plugin implements Listener
{
	public static final Random rand = new Random();
	private static final Logger log = LoggerFactory.getLogger(Unscramble.class);
	public static Unscramble instance;
	private boolean mDebug = false;

	private Session mCurrentSession = null;
	private GameConfig mAutoGame;
	private ScheduledTask mAutoGameTask;
	private MainConfig mConfig;
	private DatabaseManager mDBManager;
	public static final String channelName = "bungee:unscramble";

	private UnclaimedPrizes mUnclaimed;

	private final SimpleDateFormat mDateParser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	private final HashMap<Integer, SavedPrize> mActiveSessions = new HashMap<>();

	@Override
	public void onEnable()
	{
		instance = this;
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) getLogger().warning("Could not " +
				"create Data Dir!!!");

		getProxy().getPluginManager().registerCommand(this, new UnscrambleCommand());
		getProxy().getPluginManager().registerListener(this, this);
		getProxy().registerChannel(channelName);

		mConfig = new MainConfig(new File(getDataFolder(), "config.yml"));
		mUnclaimed = new UnclaimedPrizes(new File(getDataFolder(), "unclaimed.yml"));

		reload();
	}

	@Override
	public void onDisable()
	{
		if (mCurrentSession != null) {
			mCurrentSession.stop();
			mCurrentSession = null;
		}

		if (mAutoGameTask != null) {
			mAutoGameTask.cancel();
			mAutoGameTask = null;
		}

		if (mDBManager != null) {
			mDBManager.close();
		}
	}

	private void loadAutoGame()
	{
		if(mAutoGameTask != null)
			mAutoGameTask.cancel();
		mAutoGameTask = null;

		mAutoGame = new GameConfig();
		try
		{
			mAutoGame.init(new File(getDataFolder(), "auto.yml"));
			mAutoGame.initialize();

			if(mConfig.autoGameEnabled)
			{
				debugMsg("Starting AutoGame timer. Will only run with at least " + mAutoGame.minPlayers + " players online.");
				scheduleNextGame();
			}
		}
		catch(InvalidConfigurationException e)
		{
			mAutoGame = null;
			getLogger().severe("Could not load auto game: " + e.getMessage());
			e.printStackTrace();
		}
	}

	public ScheduledTask scheduleNextGame()
	{
		// Cancel any existing scheduled game
		if(mAutoGameTask != null)
			mAutoGameTask.cancel();
		mAutoGameTask = null;

		if(mAutoGame == null)
			return null;

		// Skew the interval time (plus or minus) by randomOffset minutes
		int offsetSecs = rand.nextInt((mAutoGame.randomOffset*60) * 2) - mAutoGame.randomOffset*60;
		debugMsg("Random offset: " + offsetSecs + " seconds (range: " + mAutoGame.randomOffset + "mins)");
		debugMsg("Next AutoGame will start in " + ((mAutoGame.interval*60) + offsetSecs) + " seconds.");

		// Schedule the task once (with random offset), then it will reschedule itself
		mAutoGameTask = getProxy().getScheduler().schedule(
				this,
				new AutoGameStarter(mAutoGame.warningPeriod, mAutoGame.minPlayers),
				(mAutoGame.interval*60) + offsetSecs,
				TimeUnit.SECONDS);
		return mAutoGameTask;
	}

	public void reload()
	{
		try
		{
			mConfig.init();
			mUnclaimed.init();
			mDebug = mConfig.debugEnabled;

			// Close any existing connections before re-establishing new database connections
			if (mDBManager != null)
				mDBManager.close();
			mDBManager = new DatabaseManager(mConfig.dbURL, mConfig.dbUsername, mConfig.dbPassword);
		}
		catch(InvalidConfigurationException e)
		{
			throw new RuntimeException(e);
		}

		loadAutoGame();
	}

	@EventHandler
	public void onPlayerChat(ChatEvent event)
	{
		if(event.getSender() instanceof ProxiedPlayer)
		{
			ProxiedPlayer player = (ProxiedPlayer)event.getSender();

			if(mCurrentSession != null)
				mCurrentSession.makeGuess(player, event.getMessage());
		}
	}

	@EventHandler
	public void onPostLogin(PostLoginEvent event) {

		if(event.getPlayer() != null)
		{
			ProxiedPlayer player = event.getPlayer();

			List<SavedPrize> prizes = getPrizes(player, false, 0);

			if(prizes != null && !prizes.isEmpty()) {
				NotifyPlayerUnclaimedPrizes(player, prizes);
			}
		}

	}

	public void onSessionFinish()
	{
		mCurrentSession = null;
	}

	public void newSession(String word, long length, long hintInterval, int hintChars, Prize prize)
	{
		if(mCurrentSession != null)
			throw new IllegalStateException("Session in progress");

		Session session = new Session(word, length, hintInterval, hintChars, prize);
		if (session.isValid()) {
			session.start();
			mCurrentSession = session;
		} else {
			ProxyServer.getInstance().getLogger().warning("BungeeUnscramble: Invalid game, aborted!");
		}
	}

	public void startAutoGame()
	{
		if(mAutoGame == null || mCurrentSession != null)
			return;

		Session session = mAutoGame.newSession();
		if(session == null)
			return;

		session.start();
		mCurrentSession = session;
	}

	public Session getSession()
	{
		return mCurrentSession;
	}

	public boolean isSessionRunning()
	{
		return mCurrentSession != null;
	}

	public void givePrize(ProxiedPlayer player, Prize prize)
	{
		// Points prizes are saved in the databsae so dont try to track them in "unclaimed"
		if (!(prize instanceof PointsPrize))
			mUnclaimed.prizes.add(new SavedPrize(player.getName(), prize));

		// Check for and remove expired prizes
		removeExpiredPrizes();

		try
		{
			mUnclaimed.save();
		}
		catch ( InvalidConfigurationException e )
		{
			e.printStackTrace();
		}
	}

	public List<SavedPrize> getPrizes(ProxiedPlayer player, boolean remove, int maxPrizesToRemove)
	{
		if(remove)
		{
			List<SavedPrize> prizes = mUnclaimed.getPrizes(player.getName(), true, maxPrizesToRemove);
			try
			{
				mUnclaimed.save();
			}
			catch(InvalidConfigurationException e)
			{
				e.printStackTrace();
			}
			return prizes;
		}
		else
			return mUnclaimed.getPrizes(player.getName(), false, maxPrizesToRemove);
	}

	public void startPrizeSession(int sessionId, ProxiedPlayer player, Prize prize, String entered)
	{
		mActiveSessions.put(sessionId, new SavedPrize(player.getName(), prize, entered));
	}

	public String getRandomWord()
	{
		if(mConfig.words.isEmpty())
			return "unscramble";

		return mConfig.words.get(rand.nextInt(mConfig.words.size()));
	}

	public MainConfig getConfig()
	{
		return mConfig;
	}
	public DatabaseManager getDatabaseManager()
	{
		return mDBManager;
	}

	@EventHandler
	public void onPluginMessage(PluginMessageEvent event)
	{
		if(!event.getTag().equals("Unscramble"))
			return;

		try (ByteArrayInputStream stream = new ByteArrayInputStream(event.getData());
			DataInputStream input = new DataInputStream(stream))
		{
			String subChannel = input.readUTF();
			int session = input.readInt();

			SavedPrize prize = mActiveSessions.remove(session);
			ProxiedPlayer player = getProxy().getPlayer(prize.player);

			if(subChannel.equals("AwardFail"))
			{
				byte hasMoreData = input.readByte();
				switch (hasMoreData) {
					case 2:
						mUnclaimed.prizes.add(prize);
						player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.RED + "An unknown error occured giving you your prizes. Please notify an admin"));
						getLogger().severe("Could not award prize: " + prize.prize.getDescription() + " (" + prize.prize.getClass().getSimpleName() + "). It was rejected by '" + player.getServer().getInfo().getName() + "'. Check that this type is handled by that server.");
						break;
					case 1:
						Entry<Prize, String> result = prize.prize.handleFail(input);
						SavedPrize newPrize = new SavedPrize(prize.player, result.getKey(), prize.entered);
						mUnclaimed.prizes.add(newPrize);
						player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.RED + result.getValue()));
						break;
					default:
						mUnclaimed.prizes.add(prize);
						player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.RED + "You can not claim the prize " + prize.prize.getDescription() + " in this location."));
						break;
				}

				try
				{
					mUnclaimed.save();
				}
				catch(InvalidConfigurationException e)
				{
					e.printStackTrace();
				}
			}
			else if(subChannel.equals("AwardOk"))
			{
				getProxy().getPlayer(prize.player).sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "You have been awarded " + ChatColor.YELLOW + prize.prize.getDescription()));
				logMsg("Awarded prize to " + prize.player + ": " + prize.prize.getDescription());
			}
		}
		catch(IOException ignored)
		{
		}
	}

	private Date ParseDate(String dateValue) {

		try {
			return mDateParser.parse(dateValue);

		} catch (ParseException ex) {
			return null;
		}

	}

	private void NotifyPlayerUnclaimedPrizes(final ProxiedPlayer player, List<SavedPrize> prizes) {

		final int prizeCount = prizes.size();

		// Determine the oldest and newest prizes
		long currentTimeMillis = System.currentTimeMillis();
		long earliestPrizeEntered = currentTimeMillis;
		long newestPrizeEntered = 0;

		long prizeExpirationDays = mConfig.prizeExpirationDays;

		// Do not allow prizes less than one week old to expire
		if(prizeExpirationDays < 7)
			prizeExpirationDays = 7;

		long expirationTimeMillis = currentTimeMillis - prizeExpirationDays * 86400 * 1000;

		for(SavedPrize prize : prizes) {

			if(prize.entered == null || prize.entered.isEmpty()) {
				prize.entered = mDateParser.format(currentTimeMillis);
			}

			Date prizeDate = ParseDate(prize.entered);

			if(prizeDate != null) {

				if(prizeDate.getTime() > expirationTimeMillis) {
					long prizeDateMillis = prizeDate.getTime();

					if(prizeDateMillis < earliestPrizeEntered) {
						earliestPrizeEntered = prizeDateMillis;
					}

					if(prizeDateMillis > newestPrizeEntered) {
						newestPrizeEntered = prizeDateMillis;
					}
				}
			}

		}

		if(newestPrizeEntered < currentTimeMillis - prizeExpirationDays * 86400 * 1000) {
			// All of the player's prizes are expired
			return;
		}

		// Warn the player if any prizes will expire soon
		long expiredPrizeWarnThreshold = currentTimeMillis - (prizeExpirationDays - mConfig.expiringPrizeWarningDays) * 86400 * 1000;

		final String expirationMessage;
		if(earliestPrizeEntered < expiredPrizeWarnThreshold) {

			if(prizeCount > 1)
				expirationMessage = ChatColor.RED + "Warning: " + ChatColor.GREEN + "You have prizes expiring soon!";
			else
				expirationMessage = ChatColor.RED + "Warning: " + ChatColor.GREEN + "Prize expires soon!";

		} else {
			expirationMessage = "";
		}

		Runnable task = () -> {

            String prizeMessage;

            if(prizeCount > 1)
                prizeMessage = ChatColor.GOLD + Integer.toString(prizeCount) + ChatColor.GREEN + " unclaimed prizes. ";
            else
                prizeMessage = ChatColor.GOLD + "1" + ChatColor.GREEN + " unclaimed prize. ";

            player.sendMessage(TextComponent.fromLegacyText(ChatColor.GRAY + "[Unscramble] " + ChatColor.GREEN + "You have " + prizeMessage + "Use " + ChatColor.GOLD + "/us claim"));

            if(!expirationMessage.isEmpty())
                player.sendMessage(TextComponent.fromLegacyText(ChatColor.GRAY + "[Unscramble] " + expirationMessage));

        };

		getProxy().getScheduler().schedule(this, task, 5, TimeUnit.SECONDS);
	}

	private void removeExpiredPrizes()
	{

		try
		{
			long prizeExpirationDays = mConfig.prizeExpirationDays;
			if(prizeExpirationDays < 7)
				prizeExpirationDays = 7;

			long expirationTimeMillis = System.currentTimeMillis() - prizeExpirationDays * 86400 * 1000;
			Iterator<SavedPrize> it = mUnclaimed.prizes.iterator();
			while(it.hasNext())
			{
				SavedPrize prize = it.next();

				if(prize.entered == null || prize.entered.isEmpty()) {
					prize.entered = mDateParser.format(System.currentTimeMillis());
				}

				Date prizeDate = ParseDate(prize.entered);

				if(prizeDate != null && prizeDate.getTime() < expirationTimeMillis) {
					logMsg("Removing expired prize for " + prize.player + ", awarded " + prize.entered + "; " + prize.prize.getDescription());
					it.remove();
				}
			}

		}
		catch ( Exception e )
		{
			e.printStackTrace();
		}
	}

	public static boolean getDebug() {
		return instance.mDebug;
	}

	public static void setDebug(boolean debug) {
		instance.mDebug = debug;
	}

	public static void logMsg(String msg) {
		instance.getLogger().info(msg);
	}

	public static void debugMsg(String msg) {
		if (instance.mDebug) {
			instance.getLogger().info(msg);
		}
	}

	public static void broadcast(String message) {
		broadcast(message, true);
	}

	public static void broadcast(String message, boolean usePrefix) {
		broadcast(message, usePrefix, true);
	}

	public static void broadcast(String message, boolean usePrefix, boolean log) {
		if (log)
			logMsg(ChatColor.stripColor(message));

		if (usePrefix)
			message = ChatColor.GREEN + "[Unscramble] " + ChatColor.YELLOW + message;

		ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(message));
	}
}