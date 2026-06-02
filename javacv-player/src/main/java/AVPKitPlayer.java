import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class AVPKitPlayer {

    // Video control states
    private static volatile boolean isPlaying = false;
    private static volatile boolean stepRequested = false;
    private static volatile boolean resetRequested = false;
    private static volatile boolean running = true;
    
    // Scrubbing states
    private static volatile int seekToFrame = -1;
    private static volatile boolean isInteracting = false; 

    public static void main(String[] args) {
        // Get the video file as an InputStream from the JAR resources
        java.io.InputStream videoStream = AVPKitPlayer.class.getResourceAsStream("/FreeFall.mp4");
        
        if (videoStream == null) {
            System.err.println("Error: Could not find FreeFall.mp4 in resources!");
            return;
        }

        // Initialize the Frame Grabber using the resource stream
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoStream);
        try {
            grabber.start();
        } catch (Exception e) {
            System.err.println("Error: Could not start the video stream.");
            e.printStackTrace();
            return;
        }

        // Fetch total video frames for our UI controls
        int totalFrames = grabber.getLengthInVideoFrames();

        // Create the CanvasFrame (the window to display the video)
        CanvasFrame canvas = new CanvasFrame("JavaCV Video Player", CanvasFrame.getDefaultGamma() / grabber.getGamma());
        canvas.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Force the video display area to exactly 400x300
        canvas.setCanvasSize(400, 300);

        // Handle window closing cleanly
        canvas.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                running = false;
            }
        });

        // Setup the Control Panels
        JPanel mainControlPanel = new JPanel(new BorderLayout());
        JPanel buttonPanel = new JPanel();
        JPanel sliderPanel = new JPanel(new BorderLayout());

        // Buttons
        JButton btnStart = new JButton("Start");
        JButton btnStop = new JButton("Stop");
        JButton btnStep = new JButton("Step");
        JButton btnReset = new JButton("Reset");

        buttonPanel.add(btnStart);
        buttonPanel.add(btnStop);
        buttonPanel.add(btnStep);
        buttonPanel.add(btnReset);

        // Slider & Frame Counter Label
        JSlider videoSlider = new JSlider(0, totalFrames > 0 ? totalFrames : 100, 0);
        JLabel lblCounter = new JLabel("Frame: 0 / " + totalFrames + "   ");
        lblCounter.setHorizontalAlignment(SwingConstants.RIGHT);

        sliderPanel.add(videoSlider, BorderLayout.CENTER);
        sliderPanel.add(lblCounter, BorderLayout.EAST);

        // Combine panels together
        mainControlPanel.add(sliderPanel, BorderLayout.NORTH);
        mainControlPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Add the combined controls to the bottom of the canvas layout
        canvas.add(mainControlPanel, BorderLayout.SOUTH);
        canvas.pack(); // Re-fit layout dimensions safely

        // Button Action Listeners
        btnStart.addActionListener(e -> isPlaying = true);
        btnStop.addActionListener(e -> isPlaying = false);
        btnStep.addActionListener(e -> {
            isPlaying = false;      
            stepRequested = true;   
        });
        btnReset.addActionListener(e -> {
            isPlaying = false;      
            resetRequested = true;  
        });

        // Slider Change Listener (for scrubbing)
        videoSlider.addChangeListener(e -> {
            // Only handle seeks triggered manually by the user dragging the slider bar
            if (videoSlider.getValueIsAdjusting()) {
                isInteracting = true;
                isPlaying = false; // Pause playback while scrubbing
                seekToFrame = videoSlider.getValue();
            } else {
                isInteracting = false;
            }
        });

        // Converter to transform JavaCV Frames to OpenCV Mats
        OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

        // Calculate delay between frames based on video frame rate (FPS)
        long frameDelay = (long) (1000 / grabber.getFrameRate());

        // Main video loop
        try {
            Frame frame;
            while (running) {
                
                // 1. Handle Explicit Seek Requests (Slider Dragging)
                if (seekToFrame != -1) {
                    grabber.setFrameNumber(seekToFrame);
                    seekToFrame = -1;
                    
                    frame = grabber.grabImage();
                    if (frame != null) {
                        Mat mat = converter.convert(frame);
                        canvas.showImage(converter.convert(mat));
                        lblCounter.setText("Frame: " + grabber.getFrameNumber() + " / " + totalFrames + "   ");
                    }
                }

                // 2. Handle Reset Request
                if (resetRequested) {
                    grabber.setFrameNumber(0);
                    resetRequested = false;
                    
                    frame = grabber.grabImage();
                    if (frame != null) {
                        Mat mat = converter.convert(frame);
                        canvas.showImage(converter.convert(mat));
                        videoSlider.setValue(0);
                        lblCounter.setText("Frame: 0 / " + totalFrames + "   ");
                    }
                }

                // 3. Handle Normal Playback / Stepping
                if (isPlaying || stepRequested) {
                    frame = grabber.grabImage();
                    
                    if (frame == null) {
                        System.out.println("End of video reached.");
                        isPlaying = false;
                        continue;
                    }

                    // Convert and display the frame
                    Mat mat = converter.convert(frame);
                    canvas.showImage(converter.convert(mat));

                    int currentFrame = grabber.getFrameNumber();
                    
                    // Update UI components dynamically (only if user isn't currently dragging it)
                    if (!isInteracting) {
                        videoSlider.setValue(currentFrame);
                    }
                    lblCounter.setText("Frame: " + currentFrame + " / " + totalFrames + "   ");

                    if (stepRequested) {
                        stepRequested = false;
                    }

                    Thread.sleep(frameDelay);
                } else {
                    // Idle sleep when paused
                    Thread.sleep(30);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Clean up resources
            try {
                grabber.stop();
                grabber.release();
                canvas.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}