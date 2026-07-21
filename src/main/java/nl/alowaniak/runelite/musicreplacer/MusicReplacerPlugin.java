package nl.alowaniak.runelite.musicreplacer;

import com.google.common.primitives.Doubles;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import javax.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static nl.alowaniak.runelite.musicreplacer.TracksOverridesUi.NORMAL_FONT;
import static nl.alowaniak.runelite.musicreplacer.TracksOverridesUi.OVERRIDE_FONT;

@Slf4j
@PluginDescriptor(
		name = "Music Replacer",
		description = "Replace music tracks with presets (e.g. OSRSBeatz) or your own music",
		tags = {"music", "replace", "override", "track", "song", "youtube", "beats", "osrsbeatz", "rs3"}
)
public class MusicReplacerPlugin extends Plugin
{

	/**
	 * Not sure how this works, but changing client music volume from 0->1 with {@link Client#setMusicVolume(int)} won't
	 * make the track play again. Using this script to turn off and on however will.
	 */
	private static final int RETRIGGER_MUSIC_SCRIPT = 9238;

	public static final String MUSIC_REPLACER_API = "https://alowan.nl/runelite-music-replacer/";
	public static final String MUSIC_REPLACER_EXECUTOR = "musicReplacerExecutor";
	public static final int PLAYING_WIDGET_ID = 8;
	public static final int CURRENTLY_PLAYING_WIDGET_ID = 9;

	/**
	 * The max the volume sliders ({@link VarPlayerID#OPTION_MASTER_VOLUME}, {@link VarPlayerID#OPTION_MUSIC}) can be
	 */
	private static final double MAX_VOL_OPTION = 100;

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
	private MusicReplacerConfig config;

	private MusicPlayer player;
	private String actualCurTrack;
	private boolean restoreActualCurTrack;
	private TrackOverride[] listofTracks;
	private TrackOverride trackToPlay;
	private int[] shuffleOrder;
	private int curSlot; 

	private double fading;

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
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			stopPlaying();
		}
	}

<<<<<<< HEAD
<<<<<<< HEAD
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
=======
	/**
	 * Tooltips need to be added before each render, so we clear it on client tick and gets added on mouse listener
	 */
	Tooltip upNextTooltip;
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (upNextTooltip != null) tooltipManager.add(upNextTooltip);
	}

	private double oldVolume = -1;
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		upNextTooltip = null;
		applyVolume(); // Always make sure we're on the right volume/fade
>>>>>>> upstream/master

		Widget curTrackWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TEXT);
		Widget playingWidget = client.getWidget(InterfaceID.Music.NOW_PLAYING_TITLE);
=======
	private double oldVolume = -1;
	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		Widget curTrackWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
		Widget playingWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, PLAYING_WIDGET_ID);
>>>>>>> parent of 138500f (fix local file single and multitrack overrides)
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
			playingWidget.setOnMouseRepeatListener((JavaScriptCallback) e -> upNextTooltip = tooltip);
			playingWidget.setOnClickListener((JavaScriptCallback) e -> restoreActualCurTrack = true);
			playingWidget.setHasListener(true);
			curTrackWidget.setText(curTrack = trackToPlay.getName());
		}

		TrackOverride[] listofTracks = tracks.getOverride(curTrack);
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


		if (fading > 0)
		{
			if ((fading -= .017) <= 0)
			{
				stopCurrentAndStartNew();
			}
		}
		else if (player != null)
		{
<<<<<<< HEAD
			double volume = (client.getMusicVolume() - 1) / MAX_VOL;
=======
			double volume = getEffectiveVolume();
>>>>>>> upstream/master
			boolean actualTrackIsBeingOverruled = config.playOverridesToEnd() && actualCurTrack != null && trackToPlay != null && !actualCurTrack.equals(trackToPlay.getName());
			if (actualTrackIsBeingOverruled && (volume <= 0 || !player.isPlaying()))
			{
				restoreActualCurTrack = true;
			}
<<<<<<< HEAD
<<<<<<< HEAD
			else if ((oldVolume <= 0 && volume > 0) || (!player.isPlaying() && (client.getVarbitValue(MUSIC_LOOP_STATE_VAR_ID) == 1 || (shuffleOrder != null && shuffleOrder.length > 1))))
=======
			else if ((oldVolume <= 0 && volume > 0) || (!player.isPlaying() && client.getVarbitValue(VarbitID.MUSIC_ENABLELOOP) == 1))
>>>>>>> upstream/master
=======
			else if ((oldVolume <= 0 && volume > 0) || (!player.isPlaying() && client.getVarbitValue(MUSIC_LOOP_STATE_VAR_ID) == 1))
>>>>>>> parent of 138500f (fix local file single and multitrack overrides)
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
					player.play();
				}
			}
			oldVolume = volume;
		}
	}

<<<<<<< HEAD
	private void stopCurrentAndStartNew() 
	{
		fading = 0;
		MusicPlayer oldPlayer = player;
		player = null;
=======
	private void stopCurrentAndStartNew() {
		stopPlaying();
>>>>>>> parent of 138500f (fix local file single and multitrack overrides)

		if (trackToPlay != null)
		{
<<<<<<< HEAD
			if (oldPlayer != null) executor.submit(oldPlayer::close);
			applyVolume();
			return;
		}

		executor.submit(() -> {
			if (oldPlayer != null) oldPlayer.close();
=======
>>>>>>> parent of 138500f (fix local file single and multitrack overrides)
			try
			{
				player = trackToPlay.getPaths()
						.filter(Files::exists)
						.map(Path::toUri)
						.map(MusicPlayer::create)
						.filter(Objects::nonNull)
						.findFirst()
						.orElse(null);

				log.warn("Player created: " + (player != null ? "YES" : "NO") + ", trackToPlay: " + trackToPlay);

				if (player != null) {
					// Set volume before playing
					double volume = (client.getMusicVolume() - 1) / MAX_VOL;
					player.setVolume(volume);
					player.play();
					log.warn("Player.play() called");
				}
				else {
					chatMsg("Deleting " + trackToPlay + " override because no player could be made (no file or wrong format?).");
					tracks.removeOverride(trackToPlay.getName());
				}
			}
			catch (OutOfMemoryError e)
			{
				log.warn("Out of memory when loading " + trackToPlay, e);
				trackToPlay = null;
			}
<<<<<<< HEAD
<<<<<<< HEAD
			applyVolume();
		});
=======
		}
>>>>>>> upstream/master
=======
		}
		else
		{
			log.warn("trackToPlay is null");
		}

		applyVolume();
>>>>>>> parent of 138500f (fix local file single and multitrack overrides)
	}

	private void applyVolume()
	{
		// Applying volume is only needed for our own player (osrs obviously handles its own volume)
		if (player == null)
<<<<<<< HEAD
		{
			int volume = (int) ((client.getMusicVolume() - 1) * multiplier);
			clientThread.invokeLater(() -> client.setMusicVolume(Ints.constrainToRange(volume, 0, (int) MAX_VOL)));
=======
		{ // But if we turned it off before we do need to "activate" it again
			var weTurnedOffMusic = client.getMusicVolume() == 0 && getEffectiveVolume() > 0;
			if (weTurnedOffMusic && client.getGameState() == GameState.LOGGED_IN) {
				// Just a setMusicVolume(>0) won't make the music start playing but running cs2 script to
				// turn music off and on again will trigger it
				var musicVol = client.getVarpValue(VarPlayerID.OPTION_MUSIC);
				client.runScript(RETRIGGER_MUSIC_SCRIPT, InterfaceID.SettingsSide.MUSIC_SLIDER_BOBBLE, 0, 116, 1);
				client.runScript(RETRIGGER_MUSIC_SCRIPT, InterfaceID.SettingsSide.MUSIC_SLIDER_BOBBLE, musicVol, 116, 1);
			}
>>>>>>> upstream/master
		}
		else
		{
			if (player.isPlaying())
			{
<<<<<<< HEAD
<<<<<<< HEAD
				double volume = Doubles.constrainToRange((cachedRealVolume - 1) / MAX_VOL * multiplier, 0, 1);
=======
				var multiplier = fading > 0 ? Math.pow(fading, 3) : 1d;
				var volume = Doubles.constrainToRange(getEffectiveVolume() * multiplier, 0, 1);
>>>>>>> upstream/master
=======
				double volume = Doubles.constrainToRange((client.getMusicVolume() - 1) / MAX_VOL * multiplier, 0, 1);
>>>>>>> parent of 138500f (fix local file single and multitrack overrides)
				player.setVolume(volume);
			}
			client.setMusicVolume(0); // Constantly applying volume 0 is not needed but ohwell
		}
	}

<<<<<<< HEAD
<<<<<<< HEAD
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
=======
	private double getEffectiveVolume() {
		var masterVol = client.getVarpValue(VarPlayerID.OPTION_MASTER_VOLUME) / MAX_VOL_OPTION;
		var musicVol = client.getVarpValue(VarPlayerID.OPTION_MUSIC) / MAX_VOL_OPTION;
		var effectiveVol = masterVol * musicVol;
		return effectiveVol * effectiveVol; // Exponential volume since we hear logarithmically
>>>>>>> upstream/master
	}

=======
>>>>>>> parent of 138500f (fix local file single and multitrack overrides)
	public void stopPlaying()
	{
		fading = 0;
		if (player != null)
		{
			player.close();
			player = null;
		}
	}

	public void forceRefreshCurrentTrack()
	{
		clientThread.invoke(() -> {
			Widget curTrackWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
			if (curTrackWidget == null) return;
			String curTrack = curTrackWidget.getText();
			if (Strings.isNullOrEmpty(curTrack)) return;
			
			// Force the plugin to re-evaluate the current track
			TrackOverride[] overrides = tracks.getOverride(curTrack);
			if (overrides != null) {
				trackToPlay = overrides[0];
				if (fading <= 0) fading = 1;
				stopCurrentAndStartNew();
			}
		});
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
			Widget curTrackWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, CURRENTLY_PLAYING_WIDGET_ID);
			Widget playingWidget = client.getWidget(WidgetID.MUSIC_GROUP_ID, PLAYING_WIDGET_ID);
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
