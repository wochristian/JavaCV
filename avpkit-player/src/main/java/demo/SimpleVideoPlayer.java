package demo;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Paths;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

// The correct, verified avpkit-core imports
import com.avpkit.mediatool.IMediaReader;
import com.avpkit.mediatool.ToolFactory;
import com.avpkit.mediatool.MediaListenerAdapter;
import com.avpkit.mediatool.event.IVideoPictureEvent;

public class SimpleVideoPlayer extends JFrame {
    private static final long serialVersionUID = 1L;
    
    private VideoPanel videoPanel;
    private String savedVideoPath;
    private IMediaReader reader;
    
    // UI Elements for progress
    private JSlider frameSlider;
    private JLabel frameLabel;
    
    // Playback state control flags
    private volatile boolean isPlaying = false;
    private volatile boolean isStepRequested = false;
    private volatile boolean hasDecodedFrame = false;
    private volatile boolean isRunning = true;
    private volatile int currentFrameCount = 0;
    
    // Framerate synchronization variables
    private volatile boolean resetSync = true;
    private long playStartSystemTimeMs = 0;
    private long playStartVideoTimeMs = 0;

    public SimpleVideoPlayer() {
        setTitle("AVPKit Video Player Controls Demo");
        setSize(800, 680); // Increased height to fit the slider panel
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());
        
        videoPanel = new VideoPanel();
        add(videoPanel, BorderLayout.CENTER);
        
        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        setVisible(true);
        startPlaybackLoop();
    }

    private JPanel createBottomPanel() {
        JPanel mainBottomPanel = new JPanel(new BorderLayout());

        // --- SLIDER PANEL ---
        JPanel sliderPanel = new JPanel(new BorderLayout(10, 0));
        frameLabel = new JLabel("Frame: 0");
        
        // Placeholder max of 1000. True duration requires probing the media container.
        frameSlider = new JSlider(0, 1000, 0); 
        frameSlider.setEnabled(false); // View-only since seeking isn't implemented
        
        sliderPanel.add(frameLabel, BorderLayout.WEST);
        sliderPanel.add(frameSlider, BorderLayout.CENTER);
        
        // --- BUTTON PANEL ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));

        JButton btnPlay = new JButton("Play");
        JButton btnStop = new JButton("Stop");
        JButton btnStep = new JButton("Single Step");
        JButton btnReset = new JButton("Reset");

        btnPlay.addActionListener(e -> {
            if (!isPlaying) {
                resetSync = true;
                isPlaying = true;
            }
        });
        
        btnStop.addActionListener(e -> {
            isPlaying = false;
            resetSync = true;
        });
        
        btnStep.addActionListener(e -> {
            isPlaying = false;
            resetSync = true; 
            isStepRequested = true;
        });
        
        btnReset.addActionListener(e -> resetVideo());

        buttonPanel.add(btnPlay);
        buttonPanel.add(btnStop);
        buttonPanel.add(btnStep);
        buttonPanel.add(btnReset);

        // Combine them
        mainBottomPanel.add(sliderPanel, BorderLayout.NORTH);
        mainBottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        return mainBottomPanel;
    }

    public synchronized void loadVideo(String videoPath) {
        this.savedVideoPath = videoPath;
        initReader();
    }

    private synchronized void initReader() {
        if (savedVideoPath == null) return;
        
        reader = ToolFactory.makeReader(savedVideoPath);
        reader.setBufferedImageTypeToGenerate(BufferedImage.TYPE_3BYTE_BGR);

        reader.addListener(new MediaListenerAdapter() {
            @Override
            public void onVideoPicture(IVideoPictureEvent event) {
                BufferedImage frame = event.getImage();
                if (frame != null) {
                    
                    // --- FRAMERATE SYNCHRONIZATION ---
                    long currentVideoTimeMs = event.getTimeStamp() / 1000;
                    
                    if (resetSync) {
                        playStartSystemTimeMs = System.currentTimeMillis();
                        playStartVideoTimeMs = currentVideoTimeMs;
                        resetSync = false;
                    } else if (isPlaying) {
                        long expectedSystemTimeMs = playStartSystemTimeMs + (currentVideoTimeMs - playStartVideoTimeMs);
                        long sleepTimeMs = expectedSystemTimeMs - System.currentTimeMillis();
                        
                        if (sleepTimeMs > 0) {
                            try {
                                Thread.sleep(sleepTimeMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    // ---------------------------------

                    currentFrameCount++;
                    
                    // Safely update the Swing UI from this background thread
                    SwingUtilities.invokeLater(() -> {
                        frameLabel.setText("Frame: " + currentFrameCount);
                        frameSlider.setValue(currentFrameCount);
                    });

                    videoPanel.updateFrame(frame);
                    hasDecodedFrame = true;
                }
            }
        });
    }

    private void startPlaybackLoop() {
        new Thread(() -> {
            try {
                while (isRunning) {
                    if (reader != null && isPlaying) {
                        if (reader.readPacket() != null) {
                            isPlaying = false;
                        }
                    } else if (reader != null && isStepRequested) {
                        hasDecodedFrame = false;
                        while (!hasDecodedFrame && isRunning) {
                            if (reader.readPacket() != null) {
                                isPlaying = false;
                                break;
                            }
                        }
                        isStepRequested = false;
                    } else {
                        Thread.sleep(15); 
                    }
                }
            } catch (Exception e) {
                System.err.println("Playback processing loop error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                cleanupReader();
            }
        }).start();
    }

    private synchronized void resetVideo() {
        isPlaying = false;
        isStepRequested = false;
        resetSync = true;
        currentFrameCount = 0;
        
        // Reset the UI graphics
        SwingUtilities.invokeLater(() -> {
            frameLabel.setText("Frame: 0");
            frameSlider.setValue(0);
        });
        
        cleanupReader();
        initReader();
    }

    private synchronized void cleanupReader() {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    private class VideoPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private BufferedImage currentFrame;

        public synchronized void updateFrame(BufferedImage frame) {
            this.currentFrame = frame;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            synchronized (this) {
                if (currentFrame != null) {
                    g.drawImage(currentFrame, 0, 0, getWidth(), getHeight(), null);
                }
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SimpleVideoPlayer player = new SimpleVideoPlayer();
            
            try {
                // 1. Open an input stream directly to the file inside the JAR
                InputStream videoStream = SimpleVideoPlayer.class.getResourceAsStream("/FreeFall.mp4");
                
                if (videoStream == null) {
                    System.err.println("Error: FreeFall.mp4 could not be found inside the JAR.");
                    return;
                }
                
                // 2. Create a temporary physical file on the OS
                File tempVideoFile = File.createTempFile("AVPKit_FreeFall_", ".mp4");
                
                // 3. Ask the OS to delete this file automatically when the app closes
                tempVideoFile.deleteOnExit();
                
                // 4. Copy the bytes from the JAR out to the physical temporary file
                Files.copy(videoStream, tempVideoFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                videoStream.close();
                
                // 5. Pass the physical absolute path to the video player
                player.loadVideo(tempVideoFile.getAbsolutePath());
                
            } catch (Exception e) {
                System.err.println("Error extracting or loading video from JAR: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}