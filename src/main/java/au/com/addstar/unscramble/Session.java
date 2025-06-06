package au.com.addstar.unscramble;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import au.com.addstar.unscramble.prizes.PointsPrize;
import au.com.addstar.unscramble.prizes.Prize;

import com.google.common.base.Joiner;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.scheduler.ScheduledTask;

public class Session implements Runnable
{
	private final String mWord;
	private String mWordScramble;

	private boolean isValid = false;

	private final long mStartTime;
	private final long mEndTime;
	private long mLastAnnounce;
	
	private String mHint;
	private final long mHintInterval;
	private final int mHintChars;
	private long mLastHint;
	
	private final Prize mPrize;
	private int mPoints;
	private int mDifficulty;
	
	private ScheduledTask mTask;
	
	private int mChatLines = 0;

	private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)[&\u00A7][0-9A-FK-OR]");

	// Define Scrabble letter values
	private static final Map<Character, Integer> scrabbleValues = new HashMap<>();

	static {
		scrabbleValues.put('a', 1);
		scrabbleValues.put('e', 1);
		scrabbleValues.put('i', 1);
		scrabbleValues.put('l', 1);
		scrabbleValues.put('n', 1);
		scrabbleValues.put('o', 1);
		scrabbleValues.put('r', 1);
		scrabbleValues.put('s', 1);
		scrabbleValues.put('t', 1);
		scrabbleValues.put('u', 1);
		scrabbleValues.put('d', 2);
		scrabbleValues.put('g', 2);
		scrabbleValues.put('b', 3);
		scrabbleValues.put('c', 3);
		scrabbleValues.put('m', 3);
		scrabbleValues.put('p', 3);
		scrabbleValues.put('f', 4);
		scrabbleValues.put('h', 4);
		scrabbleValues.put('v', 4);
		scrabbleValues.put('w', 4);
		scrabbleValues.put('y', 4);
		scrabbleValues.put('k', 5);
		scrabbleValues.put('j', 8);
		scrabbleValues.put('x', 8);
		scrabbleValues.put('q', 10);
		scrabbleValues.put('z', 10);
	}

	// Words that have their score reduced by 70%
	private static final Set<String> commonWords = new HashSet<>(Arrays.asList(
			"a", "an", "the", "is", "are", "was", "were", "and", "or", "but",
			"if", "then", "that", "this", "it", "of", "on", "in", "at", "to",
			"with", "for", "from", "by", "about", "as", "into", "like", "through",
			"after", "over", "between", "out", "up", "down", "all", "no", "not",
			"some", "more", "most", "few", "fewer", "many", "much", "any", "every",
			"other", "such", "only", "just", "also", "very", "really", "even", "well",
			"now", "then", "there", "here", "how", "where", "when", "why", "what", "which"
	));

	// Words that have their score reduced by 30%
	private static final Set<String> minecraftWords = new HashSet<>(Arrays.asList(
			"block", "pickaxe", "sword", "wool", "axe", "shovel", "stone", "dirt", "grass", "diamond",
			"gold", "iron", "coal", "wood", "plank", "log", "torch", "bed", "chest", "door", "craft",
			"table", "armor", "helmet", "boots", "zombie", "creeper", "skeleton", "spider", "enderman",
			"villager", "emerald", "fish", "bow", "arrow", "food", "water", "lava", "bucket", "boat",
			"rail", "minecart", "redstone", "lever", "button", "piston", "portal", "nether", "end",
			"bee", "sheep", "cow", "pig", "horse", "cat", "dog", "fox", "panda", "sugar", "cane",
			"cake", "cookie", "apple", "carrot", "potato", "pumpkin", "melon", "seeds", "wheat",
			"bread", "map", "compass", "clock", "shield", "banner", "sign", "book", "ink", "string",
			"leather", "bone", "slime", "clay", "sand", "gravel", "glass", "ice", "snow", "brick",
			"wool", "carpet", "bedrock", "sandstone", "torch", "eye", "ender"
	));

	public Session(String word, long duration, long hintInterval, int hintChars, Prize prize)
	{
		if(word.isEmpty())
			word = Unscramble.instance.getRandomWord();
		
		mWord = word;
		mStartTime = System.currentTimeMillis();
		mEndTime = mStartTime + duration;
		
		mHint = word.replaceAll("[^ ]", "*");
		mHintInterval = hintInterval;
		mHintChars = hintChars;

		mPrize = prize;
		scramble();
	}

	public boolean isValid() {
		return isValid;
	}

	public void setValid(boolean valid) {
		isValid = valid;
	}

	public void start()
	{
		mDifficulty = getWordDifficulty(mWord);
		mPoints = getPointsForDifficulty(mDifficulty);

		// For Points prizes, make sure we set the points and difficulty
		if (mPrize instanceof PointsPrize) {
			((PointsPrize) mPrize).setDifficulty(mDifficulty);
			((PointsPrize) mPrize).setPoints(mPoints);
		}

		String unscrambleMessage = ChatColor.DARK_AQUA + "New Game! Unscramble " + ChatColor.ITALIC + "this: ";

		if(mWordScramble.length() >= 15) {
			Unscramble.broadcast(unscrambleMessage);
			Unscramble.broadcast(ChatColor.RED + mWordScramble);
		} else {
			Unscramble.broadcast(ChatColor.RED + mWordScramble);
		}

		if(mPrize != null)
			Unscramble.broadcast(ChatColor.DARK_AQUA + "The prize for winning is " + ChatColor.YELLOW + mPrize.getDescription());
		
		mTask = ProxyServer.getInstance().getScheduler().schedule(Unscramble.instance, this, 0, 1, TimeUnit.SECONDS);
		mLastHint = System.currentTimeMillis();
	}
	
	public void stop()
	{
		mTask.cancel();
		mTask = null;
		Unscramble.instance.onSessionFinish();
		
		Unscramble.broadcast(ChatColor.DARK_AQUA + "Oh! Sorry, the game was cancelled.");
		if(Unscramble.instance.getConfig().displayAnswer)
			Unscramble.broadcast(ChatColor.DARK_AQUA + "The answer was... " + ChatColor.RED + mWord);
	}
	
	public void doHint()
	{
		// Do nothing if we've already revealed too much
		if (countChar(mHint, '*') <= mHintChars)
			return;

		// Reveal the necessary number of chars
		int charsRevealed = 0;
		while(true)
		{
			int index = Unscramble.rand.nextInt(mWord.length());
			
			char c = mWord.charAt(index);
			char hintC = mHint.charAt(index);
			
			if(c != ' ' && hintC == '*')
			{
				char[] chars = mHint.toCharArray();
				chars[index] = c;
				mHint = new String(chars);
				charsRevealed++;
				if (charsRevealed >= mHintChars)
					break;
			}
		}
		
		Unscramble.broadcast(ChatColor.DARK_AQUA + "Hint!... " + mHint, true, false);
	}
	
	private boolean isRunning()
	{
		return mTask != null;
	}
	
	public void makeGuess(final ProxiedPlayer player, String guess)
	{
		if(!isRunning())
			return;

		// Remove all colour codes from the guess
		guess = STRIP_COLOR_PATTERN.matcher(guess).replaceAll("");

		// Remove leading ! if message has one (for global chat)
		if(guess.startsWith("!"))
			guess = guess.substring(1);

		if(mWord.equalsIgnoreCase(guess)) {

			// Check for too many capital letters
			if(guess.matches(".*[A-Z ]{10,200}.*")) {
				ProxyServer.getInstance().getScheduler().schedule(Unscramble.instance, () -> player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.YELLOW + "Answer rejected: " + ChatColor.RED + "too many caps")), 500, TimeUnit.MILLISECONDS);

				return;
			}

			// Calculate the time taken to solve the word (rounded to 2 decimal places)
			final double duration = Math.round((getTimeRunning() / 1000d) * 100.0) / 100.0;

			mTask.cancel();
			mTask = null;
			
			Unscramble.instance.onSessionFinish();
			
			ProxyServer.getInstance().getScheduler().schedule(Unscramble.instance, () -> {
                Unscramble.broadcast(ChatColor.DARK_AQUA + "Congratulations " + ChatColor.stripColor(player.getDisplayName()) + "!");
                if(mPrize != null) {
					Unscramble.instance.givePrize(player, mPrize);
					DatabaseManager.PlayerRecord rec = Unscramble.instance.getDatabaseManager().getRecord(player.getUniqueId());
					Unscramble.instance.getDatabaseManager().saveRecord(rec.playerWin(mPoints));
					if (mPrize instanceof PointsPrize) {
						player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "You now have " + ChatColor.GOLD + rec.getPoints() + ChatColor.DARK_AQUA + " unscramble points."));
						// If points are less than 5 (or multiple of 25), tell the player to check their stats
						if ((rec.getWins() <= 5) || (rec.getPoints() % 25 == 0)) {
							String claimmsg = ChatColor.translateAlternateColorCodes('&', Unscramble.instance.getConfig().claimMessage);
							player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + claimmsg));
							player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.LIGHT_PURPLE + "Type " + ChatColor.AQUA + "/us stats" + ChatColor.LIGHT_PURPLE + " to view your unscramble stats."));
						}
					} else {
						player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "Use " + ChatColor.RED + "/us claim" + ChatColor.DARK_AQUA + " to claim your prize!"));
					}
					Unscramble.instance.getDatabaseManager().saveWin(player.getUniqueId(), mWord, mDifficulty, mPoints, duration);
				}
            }, 200, TimeUnit.MILLISECONDS);
		}
		else
		{
			++mChatLines;
			if(mChatLines > 10)
			{
				mChatLines = 0;
				Unscramble.broadcast(ChatColor.DARK_AQUA + "Again, the word was... " + ChatColor.RED + mWordScramble, true, false);
			}
		}
		
	}
	
	@Override
	public void run()
	{
		long left = getTimeLeft();
		
		if(left <= 0)
		{
			Unscramble.broadcast(ChatColor.DARK_AQUA + "Oh! Sorry, you didnt get the word in time!");
			if(Unscramble.instance.getConfig().displayAnswer)
				Unscramble.broadcast(ChatColor.DARK_AQUA + "The answer was... " + ChatColor.RED + mWord);

			mTask.cancel();
			mTask = null;
			
			Unscramble.instance.onSessionFinish();
			return;
		}
		
		long sinceLastAnnounce = System.currentTimeMillis() - mLastAnnounce;
		
		boolean announce = false;
		if(sinceLastAnnounce >= 1000 && left <= 3000)
			announce = true;
		//else if(sinceLastAnnounce >= 5000 && left <= 20000)
		//	announce = true;
		else if(sinceLastAnnounce >= 10000 && left <= 30000)
			announce = true;
		else if(sinceLastAnnounce >= 15000 && left <= 60000)
			announce = true;
		else if(sinceLastAnnounce >= 30000)
			announce = true;
		
		if(announce)
		{
			mLastAnnounce = System.currentTimeMillis();
			Unscramble.broadcast(ChatColor.DARK_AQUA + getTimeLeftString(), true, false);
		}
		
		if(mHintInterval != 0 && System.currentTimeMillis() - mLastHint >= mHintInterval)
		{
			doHint();
			mLastHint = System.currentTimeMillis();
		}
	}

	private long getTimeRunning()
	{
		return System.currentTimeMillis() - mStartTime;
	}

	private long getTimeLeft()
	{
		return mEndTime - System.currentTimeMillis();
	}
	
	private String getTimeLeftString()
	{
		long time = getTimeLeft();
		time = (long)Math.ceil(time / 1000D) * 1000;
		
		StringBuilder buffer = new StringBuilder();
		
		long minutes = TimeUnit.MINUTES.convert(time, TimeUnit.MILLISECONDS);
		if(minutes > 0)
		{
			if(minutes == 1)
				buffer.append("1 Minute");
			else
			{
				buffer.append(minutes);
				buffer.append(" Minutes");
			}
			time -= TimeUnit.MILLISECONDS.convert(minutes, TimeUnit.MINUTES);
		}
		
		long seconds = TimeUnit.SECONDS.convert(time, TimeUnit.MILLISECONDS);
		if(seconds > 0)
		{
			if(buffer.length() > 0)
				buffer.append(" ");
			
			if(seconds == 1)
				buffer.append("1 Second");
			else
			{
				buffer.append(seconds);
				buffer.append(" Seconds");
			}
		}
		
		buffer.append(" Left");
		
		return buffer.toString();
	}
	
	private void scramble()
	{
		String[] words = mWord.split(" ");
		
		for(int i = 0; i < words.length; ++i)
		{
			String word = words[i];
			
			if(word.length() <= 1)
				continue;
			
			ArrayList<Character> chars = new ArrayList<>(word.length());
			for(int c = 0; c < word.length(); ++c)
				chars.add(word.charAt(c));

			int maxtimes = 1000;
			int times = 0;
			while((word.equals(words[i])
					|| word.equals("shit")
					|| word.equals("craps")
					|| word.equals("parts")
					|| word.equals("piss"))
						&& times < maxtimes) // Avoid same word or offensive words
			{
				times++;
				Collections.shuffle(chars, Unscramble.rand);
				
				StringBuilder builder = new StringBuilder(word.length());
				for (Character aChar : chars) builder.append(aChar);
				word = builder.toString();
			}
			if (times >= maxtimes) {
				Logger l = ProxyServer.getInstance().getLogger();
				l.warning("BungeeUnscramble: Unable to find valid word shuffle after 1000 times!");
				l.warning("Phrase: \"" + mWord + "\"");
				l.warning("Word: \"" + words[i] + "\"");
				l.warning("Last attempt: \"" + word + "\"");
				setValid(false);
			}
			
			words[i] = word;
		}
		
		StringBuilder builder = new StringBuilder();
		for(int i = 0; i < words.length; ++i)
		{
			if(i != 0)
				builder.append(" ");
			builder.append(words[i]);
		}
		
		mWordScramble = builder.toString();
		setValid(true);
	}

	public static int getWordDifficulty(String phrase) {
		String[] words = phrase.split("\\s+");
		double adjustedScore = 0;

		for (String word : words) {
			for (char c : word.toLowerCase().toCharArray()) {
				if (scrabbleValues.containsKey(c)) {
					int letterScore = scrabbleValues.get(c);
					if (commonWords.contains(word.toLowerCase())) {
						letterScore *= 0.3; // Common english words are only worth 30% of their value
					}
					else if (minecraftWords.contains(word.toLowerCase())) {
						letterScore *= 0.7; // Common minecraft words are only worth 70% of their value
					}
					adjustedScore += letterScore;
				}
			}
		}
		int result = (int) Math.round(adjustedScore);
		Unscramble.logMsg("Difficulty: " + phrase + " = " + result);
		return result;
	}

	public int getPointsForDifficulty(int difficulty) {
		int points = 1;
		String selected = "default";
		List<String> table = Unscramble.instance.getConfig().pointsTable;

		// Walk the difficulty table to find the point range for this score
		for (String entry : table) {
			String[] parts = entry.replace(" ", ""). split(":");
			int key = Integer.parseInt(parts[0]);
			int val = Integer.parseInt(parts[1]);
			//ProxyServer.getInstance().getLogger().info("[Unscramble] Points entry: " + entry);
			// Stop looking when we find an entry greater than the difficulty
			if (key > difficulty) {
				break;
			}
			points = val;
			selected = entry;
		}
		Unscramble.logMsg("Points: " + points + " (" + selected + ")");
		return points;
	}

	public int countChar(String str, char c)
	{
		int count = 0;

		for(int i=0; i < str.length(); i++)
		{    if(str.charAt(i) == c)
			count++;
		}

		return count;
	}
}
