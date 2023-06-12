package au.com.addstar.unscramble;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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

	private final Pattern STRIP_COLOR_PATTERN;

	public Session(String word, long duration, long hintInterval, int hintChars, Prize prize)
	{
		if(word.isEmpty())
			word = Unscramble.instance.getRandomWord();
		
		mWord = word;
		mEndTime = System.currentTimeMillis() + duration;
		
		mHint = word.replaceAll("[^ ]", "*");
		mHintInterval = hintInterval;
		mHintChars = hintChars;

		STRIP_COLOR_PATTERN = Pattern.compile("(?i)[&\u00A7][0-9A-FK-OR]");

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
		String unscrambleMessage = ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "New Game! Unscramble " + ChatColor.ITALIC + "this: ";

		if(mWordScramble.length() >= 15) {
			ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(unscrambleMessage));
			ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.RED + mWordScramble));
		} else {
			ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(unscrambleMessage + ChatColor.RED + mWordScramble));
		}

		if(mPrize != null)
			ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "The prize for winning is " + ChatColor.YELLOW + mPrize.getDescription()));
		
		mTask = ProxyServer.getInstance().getScheduler().schedule(Unscramble.instance, this, 0, 1, TimeUnit.SECONDS);
		mLastHint = System.currentTimeMillis();
	}
	
	public void stop()
	{
		mTask.cancel();
		mTask = null;
		Unscramble.instance.onSessionFinish();
		
		ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "Oh! Sorry, the game was cancelled."));
		if(Unscramble.instance.getConfig().displayAnswer)
			ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "The answer was... " + ChatColor.RED + mWord));
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
		
		ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "Hint!... " + mHint));
	}
	
	private boolean isRunning()
	{
		return mTask != null;
	}
	
	public void makeGuess(final ProxiedPlayer player, String guess)
	{
		if(!isRunning())
			return;

		guess = STRIP_COLOR_PATTERN.matcher(guess).replaceAll("");

		if(mWord.equalsIgnoreCase(guess)) {

			// Check for too many capital letters
			if(guess.matches(".*[A-Z ]{10,200}.*")) {
				ProxyServer.getInstance().getScheduler().schedule(Unscramble.instance, () -> player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.YELLOW + "Answer rejected: " + ChatColor.RED + "too many caps")), 500, TimeUnit.MILLISECONDS);

				return;
			}

			mTask.cancel();
			mTask = null;
			
			Unscramble.instance.onSessionFinish();
			
			ProxyServer.getInstance().getScheduler().schedule(Unscramble.instance, () -> {
                ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "Congratulations " + ChatColor.stripColor(player.getDisplayName()) + "!"));
                if(mPrize != null)
					Unscramble.instance.givePrize(player, mPrize);
					DatabaseManager.PlayerRecord rec = Unscramble.instance.getDatabaseManager().getRecord(player.getUniqueId());
					Unscramble.instance.getDatabaseManager().saveRecord(rec.playerWin(mPoints));
					player.sendMessage(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "Use " + ChatColor.RED + "/us claim" + ChatColor.DARK_AQUA + " to claim your prize!"));
            }, 200, TimeUnit.MILLISECONDS);
		}
		else
		{
			++mChatLines;
			if(mChatLines > 10)
			{
				mChatLines = 0;
				ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "Again, the word was... " + ChatColor.RED + mWordScramble));
			}
		}
		
	}
	
	@Override
	public void run()
	{
		long left = getTimeLeft();
		
		if(left <= 0)
		{
			ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "Oh! Sorry, you didnt get the word in time!"));
			if(Unscramble.instance.getConfig().displayAnswer)
				ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + "The answer was... " + ChatColor.RED + mWord));

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
			ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(ChatColor.GREEN + "[Unscramble] " + ChatColor.DARK_AQUA + getTimeLeftString()));
		}
		
		if(mHintInterval != 0 && System.currentTimeMillis() - mLastHint >= mHintInterval)
		{
			doHint();
			mLastHint = System.currentTimeMillis();
		}
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
		// Split the phrase into individual words
		String[] words = phrase.split(" ");

		// Calculate the number of words
		int wordCount = words.length;

		// Calculate the average word length
		int totalLength = 0;
		for (String word : words) {
			totalLength += word.length();
		}
		int averageLength = (int) Math.round((double) totalLength / wordCount);

		// Calculate the complexity for each word
		int totalComplexity = 0;
		List<Integer> wordScores = new ArrayList<>();
		for (String word : words) {
			int wordComplexity = calculateWordComplexity(word);
			totalComplexity += wordComplexity;
			wordScores.add(wordComplexity);
		}

		ProxyServer.getInstance().getLogger().info("[Unscramble] Difficulty: " + phrase
				+ " = " + totalComplexity
				+ " (" + Joiner.on("+").join(wordScores) + ")");
		return totalComplexity;
	}

	private static int calculateWordComplexity(String word) {
		// Create a set of common English words
		Set<String> commonWords = new HashSet<>(Arrays.asList(
				// Add more common words as needed
				"a", "an", "the", "is", "are", "was", "were", "and", "or", "but",
				"if", "then", "that", "this", "it", "of", "on", "in", "at", "to",
				"with", "for", "from", "by", "about", "as", "into", "like", "through",
				"after", "over", "between", "out", "up", "down", "all", "no", "not",
				"some", "more", "most", "few", "fewer", "many", "much", "any", "every",
				"other", "such", "only", "just", "also", "very", "really", "even", "well",
				"now", "then", "there", "here", "how", "where", "when", "why", "what", "which"
		));

		// Count the number of unique characters in the word
		Set<Character> uniqueCharacters = new HashSet<>();
		for (int i = 0; i < word.length(); i++) {
			uniqueCharacters.add(word.charAt(i));
		}

		// Calculate the complexity based on the number of unique characters
		int complexity = uniqueCharacters.size();

		// Reduce complexity for common English words
		if (commonWords.contains(word.toLowerCase())) {
			complexity = (int) Math.max(1, complexity - 1);
		}

		return complexity;
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
		ProxyServer.getInstance().getLogger().info("[Unscramble] Points: " + points + " (" + selected + ")");
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
