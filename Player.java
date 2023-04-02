import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private boolean skip_next = false;

    private boolean skip_prev = false;

    private boolean in_use = false;

    private boolean pause = false;

    private Lock lock = new ReentrantLock();

    private final Condition lockCondition = lock.newCondition();

    private PlayerWindow window;

    private Song currentSong;

    private int num_songs = 0;

    public int getSongIndex(String id){
        for(int i = 0; i < num_songs; i++){
            if(songInfoList.get(i)[5] == id){
                return i;
            }
        }
        return -1;
    }

    public String[][] copyToQueue(){
        String[][] tempArray = new String[num_songs][6];
        for(int i = 0; i < num_songs; i++){
            tempArray[i] = songInfoList.get(i);
        }
        return tempArray;
    }

    //private Song[] songQueue = new Song[num_songs];

    private ArrayList<String[]> songInfoList = new ArrayList<>(num_songs);

    private String[][] songQueueInfo = new String[num_songs][6];

    private ArrayList<Song> songQueue = new ArrayList<>(num_songs);

    private boolean playingSong = false;

    private boolean player = false;

    private int currentFrame = 0;

    // Pressiona o botão PLAY NOW (iniciar a tocagem de músicas)
    private final ActionListener buttonListenerPlayNow =
            e -> new Thread(() -> {
                try{
                    pause = true;
                    player = true;
                    playingSong = true;
                    String idSong = window.getSelectedSong();
                    int index = getSongIndex(idSong);
                    currentSong = songQueue.get(index);
                    currentFrame = 0;
                    window.setPlayPauseButtonIcon(1);

                    this.device = FactoryRegistry.systemRegistry().createAudioDevice();
                    this.device.open(this.decoder = new Decoder());
                    this.bitstream = new Bitstream(currentSong.getBufferedInputStream());

                    while (player) {
                        window.setEnabledPlayPauseButton(true);
                        window.setEnabledNextButton(index != num_songs - 1);
                        window.setEnabledPreviousButton(index != 0);
                        window.setEnabledLoopButton(false);
                        window.setEnabledShuffleButton(false);
                        window.setEnabledStopButton(true);
                        window.setEnabledScrubber(true);

                        while (playingSong) {
                            if (!skip_next && !skip_prev) {
                                if (playNextFrame()) {
                                    window.setTime((int) (currentFrame * currentSong.getMsPerFrame()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));
                                    currentFrame++;
                                }
                            }
                            else {
                                bitstream.close();
                                device.close();
                                if(skip_prev){
                                    index--;
                                    skip_prev = false;
                                    currentSong = songQueue.get(index);
                                    window.setPlayingSongInfo(currentSong.getTitle(), currentSong.getAlbum(), currentSong.getArtist());
                                } else {
                                    index++;
                                    skip_next = false;
                                    currentSong = songQueue.get(index);
                                    window.setPlayingSongInfo(currentSong.getTitle(), currentSong.getAlbum(), currentSong.getArtist());
                                }
                                window.setEnabledPreviousButton(index != 0);
                                window.setEnabledNextButton(index != num_songs - 1);
                                if(index < num_songs){
                                    //usar lock.trylock aqui dps?
                                    currentSong = songQueue.get(index);

                                    this.device = FactoryRegistry.systemRegistry().createAudioDevice();
                                    this.device.open(this.decoder = new Decoder());
                                    this.bitstream = new Bitstream(currentSong.getBufferedInputStream());

                                    currentFrame = 0;
                                } else {
                                    playingSong = !playingSong;
                                    Thread.interrupted();
                                }
                            }


                            //window.setPlayPauseButtonIcon(0);
                            //playingSong = !playingSong;
                            //Thread.interrupted();
                        }

                    };


                } catch (JavaLayerException ex) {
                    throw new RuntimeException(ex);
                } catch (FileNotFoundException ex) {
                    throw new RuntimeException(ex);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                try {
                    bitstream.close();
                } catch (BitstreamException ex) {
                    throw new RuntimeException(ex);
                }
                device.close();
                Thread.interrupted();
            }).start();

    // Pressiona o botão REMOVE
    private final ActionListener buttonListenerRemove =
            e -> new Thread(() -> {
                try{
                    //if(lock.tryLock()){
                    //lock.lock();
                    //try{
                    String idRemove = window.getSelectedSong();
                    int idxRmv = getSongIndex(idRemove);
                    if(currentSong == songQueue.get(idxRmv)){
                        player = false;
                        playingSong = false;
                        window.resetMiniPlayer();
                    }
                    songQueue.remove(idxRmv);
                    songInfoList.remove(idxRmv);
                    num_songs--;

                    String[][] songQueueInfo;

                    songQueueInfo = copyToQueue();

                    window.setQueueList(songQueueInfo);
                    //} finally { lock.unlock()}
                    //else{ (pede para a thread esperar?}

                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).start();

    // Pressiona o botão ADD SONG
    private final ActionListener buttonListenerAddSong =
            e -> new Thread(()-> {
                try{
                    Song new_song = window.openFileChooser();
                    String[] song_info = new_song.getDisplayInfo();
                    //if(lock.tryLock()){
                    //lock.lock();
                    //try{
                    songQueue.add(new_song);
                    songInfoList.add(song_info);
                    num_songs++;

                    String[][] songQueueInfo;

                    songQueueInfo = copyToQueue();

                    window.setQueueList(songQueueInfo);
                    //} finally { lock.unlock()}
                    //else{ (pede para a thread esperar?}

                } catch (InvalidDataException err) {
                    throw new RuntimeException(err);
                } catch (UnsupportedTagException err) {
                    throw new RuntimeException(err);
                } catch (IOException err) {
                    throw new RuntimeException(err);
                } catch (BitstreamException err) {
                    throw new RuntimeException(err);
                }
            }).start();

    // Pressiona o botão PLAY/PAUSE
    private final ActionListener buttonListenerPlayPause = e -> {
        int frame = currentFrame;
        if (playingSong && pause) {
            playingSong = false;
            window.setPlayPauseButtonIcon(0);
            pause = false;
        }
        else {
            window.setPlayPauseButtonIcon(1);
            playingSong = true;
            currentFrame = frame;
            pause = true;
        }
    };

    // Pressiona o botão STOP
    private final ActionListener buttonListenerStop = e-> {
        window.resetMiniPlayer();
        playingSong = false;
    };

    // Pressiona o botão NEXT [Entrega 2]
    private final ActionListener buttonListenerNext = e->{
        //colocar um lock aqui?
        skip_next = true;
    };

    // Pressiona o botão PREVIOUS [Entrega 2]
    private final ActionListener buttonListenerPrevious = e->{
        //colocar um lock aqui?
        skip_prev = true;
    };

    // Pressiona o botão SHUFFLE [Entrega 3]
    private final ActionListener buttonListenerShuffle = null;

    // Pressiona o botão LOOP [Entrega 3]
    private final ActionListener buttonListenerLoop = null;

    // Ações com o mouse no Scrubber (a barra de progresso da música) [Entrega 2]
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
            playingSong = false;
            window.setTime((int) (window.getScrubberValue()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));
            try {
                skipToFrame((int) (window.getScrubberValue()/currentSong.getMsPerFrame()));
            } catch (BitstreamException ex) {
                throw new RuntimeException(ex);
            }
            if (pause) {
                playingSong = true;
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            window.setTime((int) (window.getScrubberValue()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            window.setTime((int) (window.getScrubberValue()), (int) (currentSong.getNumFrames() * currentSong.getMsPerFrame()));
        }
    };

    public Player() {
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "TITULO_DA_JANELA",
                songQueueInfo,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">

    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException, InterruptedException {
        lock.lock();
        try {
            if (device != null) {
                Header h = bitstream.readFrame();
                if (h == null) return false;
                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                device.write(output.getBuffer(), 0, output.getBufferLength());
                bitstream.closeFrame();
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        lock.lock();
        try {
            Header h = bitstream.readFrame();
            if (h == null) return false;
            bitstream.closeFrame();
            currentFrame++;
        }
        finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     * @throws BitstreamException Generic Bitstream exception.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        lock.lock();
        try {
            if (newFrame > currentFrame) {
                int framesToSkip = newFrame - currentFrame;
                boolean condition = true;
                while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
            }
        }
        finally {
            lock.unlock();
        }
    }
    //</editor-fold>
}
