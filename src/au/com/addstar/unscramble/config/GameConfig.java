package au.com.addstar.unscramble.config;

import java.util.ArrayList;
import java.util.List;

import au.com.addstar.unscramble.Session;
import au.com.addstar.unscramble.Unscramble;

import net.cubespace.Yamler.Config.Comment;
import net.cubespace.Yamler.Config.Config;
import net.cubespace.Yamler.Config.Path;

public class GameConfig extends Config
{
	public GameConfig()
	{
		wordList = new ArrayList<String>();
	}
	
	@Comment("The time between games in minutes. This time includes the length of the game, so it is the real interval.")
	public int interval = 15;
	
	@Comment("The length of the game in seconds")
	public int length = 30;
	
	@Path("hint-interval")
	@Comment("The time between each hint given in seconds. 0 will disable hints")
	public int hintInterval = 12;
	
	@Path("words")
	@Comment("The words the game can draw from. You can have as many words as you want and they MAY include spaces")
	public List<String> wordList;
	
	public Session newSession()
	{
		if(wordList.isEmpty())
			return null;
		
		return new Session(wordList.get(Unscramble.rand.nextInt(wordList.size())), length * 1000, hintInterval * 1000);
	}
}