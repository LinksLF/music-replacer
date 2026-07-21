package nl.alowaniak.runelite.musicreplacer;

import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.music.MusicConfig;
import net.runelite.client.plugins.music.MusicPlugin;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.gameval.InterfaceID;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static nl.alowaniak.runelite.musicreplacer.TracksOverridesUi.NORMAL_FONT;
import static nl.alowaniak.runelite.musicreplacer.TracksOverridesUi.OVERRIDE_FONT;
import static nl.alowaniak.runelite.musicreplacer.MusicReplacerConfig.CONFIG_GROUP;

@Slf4j
@PluginDescriptor(
		name = "Music Replacer",
		description = "Replace music tracks with presets (e.g. OSRSBeatz) or your own music",
		tags = {"music", "replace", "override", "track", "song", "youtube", "beats", "osrsbeatz", "rs3"}
)
@PluginDependency(MusicPlugin.class)
public class MusicReplacerPlugin extends Plugin
{
	static {
		MusicPlayer.preloadNecessaries();
	}

	public static final String MUSIC_REPLACER_API = "https://alowan.nl/runelite-music-replacer/";
	public static final String MUSIC_REPLACER_EXECUTOR = "musicReplacerExecutor";

	private static final int MUSIC_LOOP_STATE_VAR_ID = 4137;
	private static final double MAX_VOL = 255;

	@Override
	public void configure(Binder binder)
	{
		// Use our own ExecutorService instead of ScheduledExecutorService because the downloads can take a while
		binder.bind(ExecutorService.class).annotatedWith(Names.named(MUSIC_REPLACER_EXECUTOR)).toInstance(Executors.newSingleThreadExecutor());
	}

	@Inject
	private Client client;
	@Inject
	private EventBus eventBus;
	@Inject
	private ClientThread clientThread;
	@Inject
	private TooltipManager tooltipManager;

	@Inject
	private Tracks tracks;
	@Inject
	private TracksOverridesUi tracksOverridesUi;
	@Inject
	@Named(MUSIC_REPLACER_EXECUTOR)
	private ExecutorService executor;

	@Inject
	private MusicConfig musicConfig;
	@Inject
	private MusicReplacerConfig config;

	private volatile MusicPlayer player;
	private String actualCurTrack;
	private String lastCurTrack;
	private boolean restoreActualCurTrack;
	private TrackOverride[] listofTracks;
	private TrackOverride trackToPlay;
	private int[] shuffleOrder;
	private int curSlot;

	private double fading;
	private int cachedRealVolume = 255;
	private double oldVolume = -1;

	@Override
	protected void startUp()
	{
		eventBus.register(tracksOverridesUi);
		log.warn("### MUSIC REPLACER TEST BUILD - VERSION CHECK 12345 ###");
	}

	@Provides
	MusicReplacerConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MusicReplacerConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (CONFIG_GROUP.equals(configChanged.getGroup()) && configChanged.getKey().startsWith(Tracks.OVERRIDE_CONFIG_KEY_PREFIX))
		{
			lastCurTrack = null; // force re-evaluation next tick
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			stopPlaying();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		if (player == null && fading <= 0)
		{
			int liveVolume = client.getMusicVolume();
			if (liveVolume > 0 || cachedRealVolume == 0)
			{
				cachedRealVolume = liveVolume;
			}
		}

		Widget curTrackWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		Widget playingWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TITLE);
		if (curTrackWidget == null || playingWidget == null) return;

		String curTrack = curTrackWidget.getText();
		if (Strings.isNullOrEmpty(curTrack)) return;

		// I would rather do UI kinda stuff in TracksOverridesUi, but let's do it here for now
		if (restoreActualCurTrack)
		{
			restoreActualCurTrack = false;
			curTrackWidget.setText(curTrack = actualCurTrack);
			playingWidget.setFontId(NORMAL_FONT);
			playingWidget.setHasListener(false);
		}
		else if (config.playOverridesToEnd() && trackToPlay != null && !curTrack.equals(trackToPlay.getName()))
		{
			// curTrack is a new one, so keep track of actual playing track and change widget
			actualCurTrack = curTrack;
			playingWidget.setFontId(OVERRIDE_FONT);
			Tooltip tooltip = new Tooltip("Up next: " + actualCurTrack);
			playingWidget.setOnMouseRepeatListener((JavaScriptCallback) e ->
			{
				if (!tooltipManager.getTooltips().contains(tooltip)) tooltipManager.add(tooltip);
			});
			playingWidget.setOnClickListener((JavaScriptCallback) e -> restoreActualCurTrack = true);
			playingWidget.setHasListener(true);
			curTrackWidget.setText(curTrack = trackToPlay.getName());
			// Sometimes a track tries to come through briefly, this might fix that hopefully?
			applyVolume();
		}

		if (!Objects.equals(curTrack, lastCurTrack))
		{
			lastCurTrack = curTrack;
			listofTracks = tracks.getOverride(curTrack);
			TrackOverride newTrack;

			if (listofTracks == null)
			{
				newTrack = null;
			}
			else if (listofTracks.length > 1)
			{
				shuffleOrder = new int[listofTracks.length];
				for (int i = 0; i < shuffleOrder.length; i++) shuffleOrder[i] = i;
				Random rand = new Random();
				for (int i = shuffleOrder.length - 1; i > 0; i--)
				{
					int j = rand.nextInt(i + 1);
					int temp = shuffleOrder[i];
					shuffleOrder[i] = shuffleOrder[j];
					shuffleOrder[j] = temp;
				}
				curSlot = 0;
				newTrack = listofTracks[shuffleOrder[0]];
			}
			else
			{
				shuffleOrder = null;
				newTrack = listofTracks[0];
			}

			if (!Objects.equals(trackToPlay, newTrack))
			{
				trackToPlay = newTrack;
				if (fading <= 0) fading = 1;
			}
		}

		if (fading > 0)
		{
			applyVolume(fading -= .01);

			if (fading <= 0)
			{
				stopCurrentAndStartNew();
			}
		}
		else if (player != null)
		{
			double volume = (client.getMusicVolume() - 1) / MAX_VOL;
			boolean actualTrackIsBeingOverruled = config.playOverridesToEnd() && actualCurTrack != null && trackToPlay != null && !actualCurTrack.equals(trackToPlay.getName());
			if (actualTrackIsBeingOverruled && (volume <= 0 || !player.isPlaying()))
			{
				restoreActualCurTrack = true;
			}
			else if ((oldVolume <= 0 && volume > 0) || (!player.isPlaying() && (client.getVarbitValue(MUSIC_LOOP_STATE_VAR_ID) == 1 || (shuffleOrder != null && shuffleOrder.length > 1))))
			{
				// Restart play if
				// we switched from muted to on (mimic osrs behavior)
				// or we ended and have loop enabled
				if (shuffleOrder != null && shuffleOrder.length > 1)
				{
					curSlot = (curSlot + 1) % shuffleOrder.length;
					trackToPlay = listofTracks[shuffleOrder[curSlot]];
					stopCurrentAndStartNew();
				}
				else
				{
					startPlayerSafe(player);
				}
			}

			applyVolume();
			oldVolume = volume;
		}
	}

	private void stopCurrentAndStartNew() 
	{
		fading = 0;
		MusicPlayer oldPlayer = player;
		player = null;

		TrackOverride toPlay = trackToPlay;
		if (toPlay == null)
		{
			if (oldPlayer != null) executor.submit(oldPlayer::close);
			applyVolume();
			return;
		}

		executor.submit(() -> {
			if (oldPlayer != null) oldPlayer.close();
			try
			{
				MusicPlayer newPlayer = toPlay.getPaths()
						.filter(Files::exists)
						.map(Path::toUri)
						.map(MusicPlayer::create)
						.filter(Objects::nonNull)
						.findFirst()
						.orElse(null);

				if (newPlayer != null)
				{
					double volume = Math.max((cachedRealVolume - 1) / MAX_VOL, 0);
					newPlayer.setVolume(0);
					newPlayer.play();
					newPlayer.setVolume(volume);
					player = newPlayer;
				}
				else
				{
					chatMsg("Deleting " + toPlay + " override because no player could be made (no file or wrong format?).");
					tracks.removeOverride(toPlay.getName());
				}
			}
			catch (OutOfMemoryError e)
			{
				log.warn("Out of memory when loading " + toPlay, e);
				trackToPlay = null;
			}
			applyVolume();
		});
	}

	private void applyVolume()
	{
		applyVolume(1);
	}

	private void applyVolume(double multiplier)
	{
		// Setting client music volume with invokeLater seems to prevent the original music from coming through
		// I'm guessing because it depends on "where" in the client loop the vol is set
		// And with invokeLater it happens to "overwrite" Music plugin's write at the correct "point" in the client loop

		if (player == null)
		{
			int volume = (int) ((cachedRealVolume - 1) * multiplier);
			clientThread.invokeLater(() -> client.setMusicVolume(Ints.constrainToRange(volume, 0, (int) MAX_VOL)));
		}
		else
		{
			if (player.isPlaying())
			{
				double volume = Doubles.constrainToRange((cachedRealVolume - 1) / MAX_VOL * multiplier, 0, 1);
				player.setVolume(volume);
			}
			clientThread.invokeLater(() -> client.setMusicVolume(0));
		}
	}

	private void startPlayerSafe(MusicPlayer player) 
	{
		if (player == null) return;
		// Read the user's current music volume (0 = muted, 255 = max)
		double targetVolume = (client.getMusicVolume() - 1) / MAX_VOL;
		if (targetVolume < 0) targetVolume = 0;

		// 1. Set volume to 0 to prevent any pop
		player.setVolume(0);
		// 2. Start playback silently
		player.play();
		// 3. Immediately set the correct volume
		player.setVolume(targetVolume);
	}

	public void stopPlaying()
	{
		fading = 0;
		if (player != null)
		{
			player.close();
			player = null;
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(tracksOverridesUi);
		tracksOverridesUi.shutdown();
		trackToPlay = null;
		stopPlaying();
		clientThread.invoke(() ->
		{
			applyVolume();
			Widget curTrackWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
			Widget playingWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TITLE);
			if (curTrackWidget == null || playingWidget == null) return;
			if (!Strings.isNullOrEmpty(actualCurTrack)) curTrackWidget.setText(actualCurTrack);
			playingWidget.setFontId(NORMAL_FONT);
			playingWidget.setHasListener(false);
			playingWidget.setOnMouseRepeatListener((Object[]) null);
			actualCurTrack = null;
		});
	}

	public void chatMsg(String... msgs) {
		clientThread.invoke(() -> Arrays.stream(msgs).forEach(msg -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null)));
	}
}
