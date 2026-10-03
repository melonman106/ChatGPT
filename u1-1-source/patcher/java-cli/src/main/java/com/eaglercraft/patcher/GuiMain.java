package com.eaglercraft.patcher;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * Collects inputs and runs the command-line patcher in a child JVM.
 * The CLI applies patches, validates input hashes, and reports progress.
 */
public final class GuiMain {
    private static final String TITLE = "Eaglercraft 26.2 u1 - BuildTools";
    private static final String CREATE_DEV = "Source project";
    private static final String BUILD_STANDALONE = "Standalone HTML";
    private static final String BUILD_IWA = "Isolated Web App";
    private static final ContentProfile NORMAL_CONTENT_PROFILE = new ContentProfile(
            "Normal", "source-patch-bundle.zip", PatchEngine.acceptedBundleSha256(),
            "resource-overlay-normal.zip", ResourceOverlay.EXPECTED_ARCHIVE_SHA256,
            "The default profile uses the Normal source bundle and resource overlay.");
    private static final String[] SOURCE_CONTENT_PROFILE_NAMES = { NORMAL_CONTENT_PROFILE.name() };
    private static final String[] STANDALONE_CONTENT_PROFILE_NAMES = { NORMAL_CONTENT_PROFILE.name() };
    private static final String[] IWA_CONTENT_PROFILE_NAMES = { NORMAL_CONTENT_PROFILE.name() };
    private static final String SETUP_TOOLS_LABEL = "Install build tools";
    private static final String AUDIO_INDEX_SHA1 = "981aab8147520cdc1f0d4a84f46c161929021fee";
    private static final String AUDIO_METADATA_SHA256 = "69cc6cfeccf235d71ffb307aa9150c784690c48f510be3036dd42f7e3050ce98";
    private static final String SOUNDS_ARCHIVE_SHA256 = "94bc8bfcf4132c52c6d5f61f3f92e50532a6fad1f5bc901ee25a462db8ba4fc6";
    private static final String MUSIC_ARCHIVE_SHA256 = "f01cdaf62a9686438998b11ffed5407a1b890e14960c63f71b57401d02216a4e";
    private static final int OFFICIAL_AUDIO_OGG_COUNT = 4871;
    private static final List<String> OUTPUT_PATH_KEYS = List.of("output", "standaloneOutput", "iwaOutput", "iwaKey");
    private static final int LOG_LIMIT = 24_000;
    private static final Pattern PROGRESS = Pattern.compile(
            "PROGRESS\\s+stage=([^\\s]+)\\s+elapsed_seconds=(\\d+)");
    private static final Pattern ERROR_PREFIX = Pattern.compile("^ERROR:\\s*(.*)$");

    private final JFrame frame = new JFrame(TITLE);
    private final JComboBox<String> mode = new JComboBox<>(new String[] { CREATE_DEV, BUILD_STANDALONE, BUILD_IWA });
    private final JComboBox<String> contentProfile = new JComboBox<>(SOURCE_CONTENT_PROFILE_NAMES);
    private final JTextArea contentProfileHint = new JTextArea(2, 48);
    private final JTextArea modeHint = new JTextArea(2, 48);
    private final JLabel buildMemorySummary = new JLabel("Build memory: checking…");
    private final JTextArea readiness = new JTextArea(2, 48);
    private final JLabel progressLabel = new JLabel("Idle");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea log = new JTextArea();
    private final JButton run = new JButton("Create project");
    private final JButton cancel = new JButton("Stop");
    private final JButton openFolder = new JButton("Open folder");
    private final JButton setupTools = new JButton(SETUP_TOOLS_LABEL);
    private final JButton advancedToggle = new JButton("Advanced...");
    private final JPanel advancedPanel = new JPanel();
    private final JPanel standaloneOutputPanel = new JPanel();
    private final JPanel iwaOutputPanel = new JPanel();
    private final JPanel webOutputPanel = new JPanel();
    private final JPanel modOptionsPanel = new JPanel(new BorderLayout(8, 4));
    private final JPanel standaloneFields = new JPanel(new GridBagLayout());
    private final JPanel wispcraftScriptPanel = new JPanel(new GridBagLayout());
    private final JPanel audioPreparationPanel = new JPanel();
    private final JCheckBox includeWispcraft = new JCheckBox("Use local Wispcraft script");
    private final JCheckBox embedMusicInHtml = new JCheckBox("Embed music in the HTML (larger file)");
    private final JCheckBox reuseExistingProject = new JCheckBox("Reuse existing patched project");
    private final JLabel musicHelp = new JLabel(
            "Default: create a sibling resource-pack ZIP; import it in Minecraft's Resource Packs menu.");
    private final JLabel iwaKeyInfo = new JLabel(" ");
    private final Map<String, JTextField> fields = new LinkedHashMap<>();
    private final Map<String, Boolean> toolVersionCache = new LinkedHashMap<>();
    private final ExecutorService cancellationExecutor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "eagler-patcher-cancel");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean audioTaskActive = new AtomicBoolean();
    private final AtomicBoolean audioBackgroundStarted = new AtomicBoolean();
    private final AtomicBoolean audioBackgroundExited = new AtomicBoolean();
    private final AtomicBoolean audioCleanupConfirmed = new AtomicBoolean(true);
    private SwingWorker<RunResult, String> worker;
    private SwingWorker<Integer, String> setupWorker;
    private SwingWorker<Void, Void> resourceWorker;
    private SwingWorker<AudioPreparationResult, String> audioWorker;
    private final JButton getOfficialResources = new JButton("Get official resources");
    private final JButton getGameAudio = new JButton("Get game audio");
    private boolean runAfterResources;
    private boolean advancedVisible;
    private boolean toolDiscoveryPending;
    private boolean cancelPending;
    private boolean audioCancelPending;
    private boolean runAfterAudio;
    private boolean runLocked;
    private final Timer progressHeartbeat = new Timer(1_000, event -> refreshProgressHeartbeat());
    private final Timer audioProgressHeartbeat = new Timer(1_000, event -> refreshAudioProgressHeartbeat());
    private final Timer projectProbeDelay = new Timer(250, event -> probeProjectAsync());
    private long projectProbeGeneration;
    private String recognizedProjectText;
    private String activeProgressStage;
    private long progressElapsedAtUpdate;
    private long progressUpdateNanos;
    private BuildMemoryBudget buildMemoryBudget;
    private Path lastSuccessfulFolder;
    private long audioStartedNanos;

    private GuiMain() {
        buildUi();
        prefillOutputPaths();
        discoverAdjacentInputs();
        watchFields();
        discoverInstalledTools();
        discoverBuildMemory();
        updateReadiness();
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--self-test")) {
            selfTest();
            return;
        }
        if (Arrays.asList(args).contains("--self-test-child")) {
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (args.length == 2 && "--self-test-child-with-descendant".equals(args[0])) {
            try {
                Process descendant = new ProcessBuilder(javaExecutable().toString(), "-cp", codeSource().toString(),
                        GuiMain.class.getName(), "--self-test-child").start();
                Files.writeString(Path.of(args[1]), Long.toString(descendant.pid()), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                Thread.sleep(30_000L);
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("process-tree self-test child failed", ex);
            }
            return;
        }
        installClassicLookAndFeel();
        javax.swing.SwingUtilities.invokeLater(() -> new GuiMain().show());
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 640));
        frame.setSize(940, 760);
        frame.setLayout(new BorderLayout(8, 8));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (setupWorker != null && !setupWorker.isDone()) {
                    progressLabel.setText("Installing tools. Please wait.");
                    return;
                }
                if (resourceWorker != null && !resourceWorker.isDone()) {
                    progressLabel.setText("Getting resources. Please wait.");
                    return;
                }
                if (audioTaskActive.get() || (audioWorker != null && !audioWorker.isDone())) {
                    progressLabel.setText(audioCancelPending
                            ? "Stopping audio and preserving verified cache. Please wait."
                            : "Preparing audio. Use Stop to cancel safely.");
                    return;
                }
                if (worker != null && !worker.isDone()) {
                    cancelRun();
                } else {
                    frame.dispose();
                }
            }
        });

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(10, 14, 4, 14));
        JLabel heading = new JLabel("Eaglercraft 26.2 u1");
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        heading.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        top.add(heading);
        JPanel modeRow = new JPanel(new BorderLayout(10, 0));
        modeRow.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        modeRow.add(new JLabel("Mode"), BorderLayout.WEST);
        modeRow.add(mode, BorderLayout.CENTER);
        top.add(modeRow);
        JPanel contentProfileRow = new JPanel(new BorderLayout(10, 0));
        contentProfileRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        contentProfileRow.add(new JLabel("Content profile"), BorderLayout.WEST);
        contentProfileRow.add(contentProfile, BorderLayout.CENTER);
        top.add(contentProfileRow);
        configureWrappedText(contentProfileHint);
        contentProfileHint.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        top.add(contentProfileHint);
        configureWrappedText(modeHint);
        modeHint.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        top.add(modeHint);
        buildMemorySummary.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        top.add(buildMemorySummary);
        frame.add(top, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        form.add(primaryPath("Minecraft 26.2 JAR", "jar", false));
        form.add(primaryPath("Project folder", "output", true));
        reuseExistingProject.setToolTipText(
                "The CLI verifies the existing project's receipt and required build files before reuse.");
        reuseExistingProject.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        reuseExistingProject.setVisible(false);
        reuseExistingProject.addActionListener(event -> updateReadiness());
        form.add(reuseExistingProject);
        standaloneOutputPanel.setLayout(new BoxLayout(standaloneOutputPanel, BoxLayout.Y_AXIS));
        standaloneOutputPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        standaloneOutputPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        standaloneOutputPanel.add(primaryPath("HTML file", "standaloneOutput", false));
        webOutputPanel.setLayout(new BoxLayout(webOutputPanel, BoxLayout.Y_AXIS));
        webOutputPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        webOutputPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        webOutputPanel.add(standaloneOutputPanel);
        iwaOutputPanel.setLayout(new BoxLayout(iwaOutputPanel, BoxLayout.Y_AXIS));
        iwaOutputPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        iwaOutputPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        iwaOutputPanel.add(primaryPath("Signed .swbn file", "iwaOutput", false));
        addPath(iwaOutputPanel, "Local signing key", "iwaKey", false);
        iwaKeyInfo.setText("Keep this private key to preserve the app's identity when rebuilding.");
        iwaKeyInfo.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        iwaOutputPanel.add(iwaKeyInfo);
        iwaOutputPanel.setVisible(false);
        webOutputPanel.add(iwaOutputPanel);
        webOutputPanel.setVisible(false);
        form.add(webOutputPanel);

        modOptionsPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Optional script (Standalone HTML only)"),
                BorderFactory.createEmptyBorder(0, 6, 6, 6)));
        modOptionsPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        JPanel modChoiceRow = new JPanel(new BorderLayout(8, 0));
        includeWispcraft.setText("Wispcraft (local compiled script)");
        includeWispcraft.setToolTipText(
                "Select an existing compiled dist/index.js. The patcher does not download or build Wispcraft.");
        modChoiceRow.add(includeWispcraft, BorderLayout.WEST);
        modOptionsPanel.add(modChoiceRow, BorderLayout.NORTH);
        wispcraftScriptPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        addPath(wispcraftScriptPanel, "Compiled Wispcraft dist/index.js", "wispcraftScript", false);
        modOptionsPanel.add(wispcraftScriptPanel, BorderLayout.CENTER);
        modOptionsPanel.setVisible(false);
        form.add(modOptionsPanel);
        includeWispcraft.addActionListener(event -> {
            wispcraftScriptPanel.setVisible(includeWispcraft.isSelected()
                    && BUILD_STANDALONE.equals(mode.getSelectedItem()));
            updateReadiness();
            frame.revalidate();
        });

        configureWrappedText(readiness);
        readiness.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        readiness.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        form.add(readiness);

        audioPreparationPanel.setLayout(new BoxLayout(audioPreparationPanel, BoxLayout.Y_AXIS));
        audioPreparationPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Official game audio (Standalone / IWA)"),
                BorderFactory.createEmptyBorder(0, 6, 6, 6)));
        audioPreparationPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        getGameAudio.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        getGameAudio.setToolTipText("Fetches the pinned official audio objects and rebuilds both EPK files.");
        getGameAudio.addActionListener(event -> getGameAudio());
        audioPreparationPanel.add(getGameAudio);
        audioPreparationPanel.add(wrappedText(
                "Downloads and verifies the official sound objects, then rebuilds both EPK files. "
                + "FFmpeg 6.1.1 must already be installed; this tool does not install FFmpeg. "
                + "Existing EPKs or pins are preserved and disable automatic publishing."));
        form.add(audioPreparationPanel);

        JPanel toolSetupRow = new JPanel();
        toolSetupRow.setLayout(new BoxLayout(toolSetupRow, BoxLayout.Y_AXIS));
        toolSetupRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 8, 4));
        toolSetupRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        toolSetupRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        setupTools.addActionListener(event -> setupTools());
        toolSetupRow.add(setupTools);
        toolSetupRow.add(wrappedText("Installs Java and Node here."));
        form.add(toolSetupRow);

        advancedPanel.setLayout(new BoxLayout(advancedPanel, BoxLayout.Y_AXIS));
        advancedPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Advanced files"),
                BorderFactory.createEmptyBorder(0, 6, 6, 6)));
        advancedPanel.add(wrappedText("Only change these if auto-detection missed a file."));

        JPanel coreFiles = new JPanel(new GridBagLayout());
        addPath(coreFiles, "Vineflower decompiler JAR", "vineflower", false);
        addPath(coreFiles, "Java 17 executable", "java17", false);
        addPath(coreFiles, "Source patch bundle ZIP", "patchBundle", false);
        addText(coreFiles, "Patch bundle SHA-256", "patchSha", "Accepted source bundle hash; checked by the CLI.");
        fields.get("patchSha").setText(PatchEngine.acceptedBundleSha256());
        fields.get("patchSha").setEditable(false);
        addPath(coreFiles, "Project skeleton ZIP", "skeleton", false);
        addText(coreFiles, "Skeleton SHA-256", "skeletonSha", "Accepted archive hash; checked by the CLI.");
        fields.get("skeletonSha").setEditable(false);
        addPath(coreFiles, "Resource overlay ZIP", "resourceOverlay", false);
        addText(coreFiles, "Overlay SHA-256", "resourceSha", "Accepted archive hash; checked by the CLI.");
        fields.get("resourceSha").setEditable(false);
        addPath(coreFiles, "External resources folder", "externalRoot", true);
        advancedPanel.add(coreFiles);
        getOfficialResources.addActionListener(event -> getOfficialResources());
        advancedPanel.add(getOfficialResources);

        standaloneFields.setBorder(BorderFactory.createTitledBorder("Web build tools and audio inputs"));
        addPath(standaloneFields, "Java 25 executable", "java25", false);
        addPath(standaloneFields, "Node.js executable", "node", false);
        addPath(standaloneFields, "FFmpeg 6.1.1 executable", "ffmpeg", false);
        addPath(standaloneFields, "npm CLI", "npm", false);
        addPath(standaloneFields, "sounds.epk", "sounds", false);
        addText(standaloneFields, "Sounds EPK SHA-256", "soundsSha", "Hash of the sound file, checked by the CLI.");
        GridBagConstraints musicChoice = constraints(0, standaloneFields.getComponentCount(), 1.0,
                GridBagConstraints.HORIZONTAL);
        musicChoice.gridwidth = 3;
        standaloneFields.add(embedMusicInHtml, musicChoice);
        musicHelp.setText("Default: create a sibling resource-pack ZIP; import it in Minecraft's Resource Packs menu.");
        GridBagConstraints musicHelpConstraints = constraints(0, standaloneFields.getComponentCount(), 1.0,
                GridBagConstraints.HORIZONTAL);
        musicHelpConstraints.gridwidth = 3;
        standaloneFields.add(musicHelp, musicHelpConstraints);
        embedMusicInHtml.addActionListener(event -> updateReadiness());
        advancedPanel.add(standaloneFields);

        advancedPanel.setVisible(false);
        standaloneFields.setVisible(false);
        wispcraftScriptPanel.setVisible(false);

        JPanel advancedControls = new JPanel(new BorderLayout(8, 0));
        advancedToggle.setBorderPainted(false);
        advancedToggle.setContentAreaFilled(false);
        advancedToggle.setHorizontalAlignment(JButton.LEFT);
        advancedToggle.addActionListener(event -> toggleAdvanced());
        advancedControls.add(advancedToggle, BorderLayout.WEST);
        advancedControls.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        advancedControls.setMaximumSize(new Dimension(Integer.MAX_VALUE, advancedControls.getPreferredSize().height));
        form.add(advancedControls);
        advancedPanel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        advancedPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        form.add(advancedPanel);

        JScrollPane formScroll = verticalScrollPane(form);
        formScroll.setBorder(BorderFactory.createEmptyBorder());
        frame.add(formScroll, BorderLayout.CENTER);

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setRows(8);
        JScrollPane logScroll = verticalScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));

        JPanel status = new JPanel(new BorderLayout(8, 4));
        status.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        progress.setStringPainted(true);
        progress.setValue(0);
        status.add(progress, BorderLayout.CENTER);
        status.add(progressLabel, BorderLayout.SOUTH);

        JPanel actions = new JPanel(new BorderLayout(8, 4));
        actions.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));
        cancel.setEnabled(false);
        cancel.addActionListener(event -> cancelRun());
        run.addActionListener(event -> startRun());
        openFolder.setEnabled(false);
        openFolder.addActionListener(event -> openLastSuccessfulFolder());
        JPanel buttons = new JPanel();
        buttons.add(run);
        buttons.add(openFolder);
        buttons.add(cancel);
        actions.add(buttons, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(logScroll, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(actions, BorderLayout.SOUTH);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.getRootPane().setDefaultButton(run);
        mode.addActionListener(event -> updateModeHint());
        contentProfile.addActionListener(event -> applyContentProfile());
        updateModeHint();
    }

    private static JTextArea wrappedText(String message) {
        JTextArea text = new JTextArea(message, 2, 42);
        configureWrappedText(text);
        text.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        text.setMaximumSize(new Dimension(Integer.MAX_VALUE, text.getPreferredSize().height));
        return text;
    }

    private static void configureWrappedText(JTextArea text) {
        text.setEditable(false);
        text.setFocusable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder());
    }

    private static void setWrappedText(JTextArea text, String message) {
        text.setText(message);
    }

    private static JScrollPane verticalScrollPane(java.awt.Component view) {
        JScrollPane scroll = new JScrollPane(view,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setScrollMode(javax.swing.JViewport.BLIT_SCROLL_MODE);
        return scroll;
    }

    private void watchFields() {
        projectProbeDelay.setRepeats(false);
        javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener() {
            private void changed(javax.swing.event.DocumentEvent event) {
                if (event.getDocument() == fields.get("output").getDocument()) queueProjectProbe();
                updateReadiness();
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { changed(event); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { changed(event); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { changed(event); }
        };
        for (JTextField field : new LinkedHashSet<>(fields.values())) {
            field.getDocument().addDocumentListener(listener);
        }
    }

    private void queueProjectProbe() {
        projectProbeGeneration++;
        recognizedProjectText = null;
        projectProbeDelay.restart();
    }

    private void probeProjectAsync() {
        if (!BUILD_STANDALONE.equals(mode.getSelectedItem()) || !hasValue("output")) return;
        String selected = fields.get("output").getText().trim();
        Path candidate;
        try {
            candidate = path("output");
        } catch (RuntimeException ex) {
            return;
        }
        long generation = projectProbeGeneration;
        cancellationExecutor.execute(() -> {
            boolean recognized = ProjectReuse.looksLikeProject(candidate);
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (!currentProjectProbe(generation, projectProbeGeneration,
                        selected, fields.get("output").getText().trim())) return;
                recognizedProjectText = recognized ? selected : null;
                updateReadiness();
            });
        });
    }

    private static boolean currentProjectProbe(long resultGeneration, long activeGeneration,
            String selected, String current) {
        return resultGeneration == activeGeneration && selected.equals(current);
    }

    private void toggleAdvanced() {
        advancedVisible = !advancedVisible;
        advancedPanel.setVisible(advancedVisible);
        advancedToggle.setText(advancedVisible ? "Hide advanced" : "Advanced...");
        frame.revalidate();
        frame.repaint();
    }

    private static void installClassicLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's default theme is fine if Metal is unavailable.
        }
    }

    private void show() {
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private void prefillOutputPaths() {
        clearOutputDefaults(fields);
    }

    private static void clearOutputDefaults(Map<String, JTextField> fields) {
        for (String key : OUTPUT_PATH_KEYS) fields.get(key).setText("");
    }

    private void discoverAdjacentInputs() {
        for (Path directory : inputDirectories()) {
            suggestFirst(directory, "jar", "minecraft-client-26.2.jar", "minecraft-26.2-client.jar",
                    "client-26.2.jar", "minecraft-client.jar", "client.jar");
            suggestFirst(directory, "vineflower", "vineflower-1.12.0.jar", "vineflower.jar");
            suggestFirst(directory, "skeleton", "project-skeleton-u1-nettyfix.zip");
            suggestFirstDirectory(directory, "externalRoot", "resources");
            suggestFirst(directory, "sounds", "sounds.epk");
        }
        if (hasValue("skeleton")) {
            fields.get("skeletonSha").setText(ProjectSkeleton.acceptedArchiveSha256());
        }
        applyContentProfile();
        if (hasValue("sounds")) {
            Path pin = path("sounds").resolveSibling("sounds.epk.sha256");
            try {
                if (Files.isRegularFile(pin) && Files.size(pin) <= 128L) {
                    String value = Files.readString(pin, StandardCharsets.UTF_8).trim();
                    if (value.matches("[0-9a-f]{64}")) {
                        fields.get("soundsSha").setText(value);
                    }
                }
            } catch (IOException ignored) {
                // A missing local pin leaves the field for the user to fill.
            }
        }
    }

    private List<Path> inputDirectories() {
        Set<Path> directories = new LinkedHashSet<>();
        Path launchDirectory = codeSource().getParent();
        if (launchDirectory != null) {
            directories.add(launchDirectory);
            directories.add(launchDirectory.resolve("inputs"));
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        directories.add(workingDirectory);
        directories.add(workingDirectory.resolve("inputs"));
        return new ArrayList<>(directories);
    }

    private void refreshBundledInputs() {
        ContentProfile profile = contentProfileSpec((String) contentProfile.getSelectedItem());
        boolean needsBundle = !readableFile("patchBundle");
        refreshBundledFile("patchBundle", profile.patchBundleName());
        if (needsBundle && readableFile("patchBundle")) fields.get("patchSha").setText(profile.patchBundleSha256());
        boolean needsSkeleton = !readableFile("skeleton");
        refreshBundledFile("skeleton", "project-skeleton-u1-nettyfix.zip");
        if (needsSkeleton && readableFile("skeleton")) fields.get("skeletonSha").setText(ProjectSkeleton.acceptedArchiveSha256());
        boolean needsOverlay = !readableFile("resourceOverlay");
        refreshBundledFile("resourceOverlay", profile.resourceOverlayName());
        if (needsOverlay && readableFile("resourceOverlay")) fields.get("resourceSha").setText(profile.resourceOverlaySha256());
        refreshBundledFile("vineflower", "vineflower-1.12.0.jar", "vineflower.jar");
        boolean needsSounds = !readableFile("sounds");
        refreshBundledFile("sounds", "sounds.epk");
        if (needsSounds && readableFile("sounds")) fields.get("soundsSha").setText(SOUNDS_ARCHIVE_SHA256);
        if (!directoryAvailable("externalRoot")) {
            for (Path directory : inputDirectories()) {
                Path resources = directory.resolve("resources");
                if (Files.isDirectory(resources) && Files.isReadable(resources)) {
                    fields.get("externalRoot").setText(resources.toAbsolutePath().normalize().toString());
                    break;
                }
            }
        }
    }

    private void refreshBundledFile(String key, String... names) {
        if (readableFile(key)) return;
        for (Path directory : inputDirectories()) {
            for (String name : names) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                    fields.get(key).setText(candidate.toAbsolutePath().normalize().toString());
                    return;
                }
            }
        }
    }

    private void suggestFirst(Path directory, String key, String... names) {
        if (hasValue(key) || !Files.isDirectory(directory)) {
            return;
        }
        for (String name : names) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                fields.get(key).setText(candidate.toAbsolutePath().normalize().toString());
                return;
            }
        }
    }

    private void suggestFirstDirectory(Path directory, String key, String name) {
        if (hasValue(key)) {
            return;
        }
        Path candidate = directory.resolve(name);
        if (Files.isDirectory(candidate) && Files.isReadable(candidate)) {
            fields.get(key).setText(candidate.toAbsolutePath().normalize().toString());
        }
    }

    private void applyContentProfile() {
        String selectedName = (String)contentProfile.getSelectedItem();
        if (!profileAvailableForMode(selectedName, (String)mode.getSelectedItem())) {
            updateAvailableContentProfiles();
            return;
        }
        ContentProfile selected = contentProfileSpec(selectedName);
        Path launchDirectory = codeSource().getParent();
        if (launchDirectory == null) {
            launchDirectory = Path.of("").toAbsolutePath().normalize();
        }
        ProfileInputs inputs = resolveProfileInputs(selected, inputDirectories(), launchDirectory);
        fields.get("patchBundle").setText(inputs.patchBundle().toString());
        fields.get("patchSha").setText(inputs.patchSha256());
        fields.get("resourceOverlay").setText(inputs.resourceOverlay().toString());
        fields.get("resourceSha").setText(inputs.resourceOverlaySha256());
        setWrappedText(contentProfileHint, selected.description());
        updateReadiness();
    }

    private static ContentProfile contentProfileSpec(String name) {
        if (NORMAL_CONTENT_PROFILE.name().equals(name)) {
            return NORMAL_CONTENT_PROFILE;
        }
        throw new IllegalArgumentException("Unknown content profile: " + name);
    }

    private static ProfileInputs resolveProfileInputs(ContentProfile profile, List<Path> searchDirectories,
            Path fallbackRoot) {
        Path patchFallback = fallbackRoot.resolve(profile.patchBundleName()).toAbsolutePath().normalize();
        Path overlayFallback = fallbackRoot.resolve("inputs").resolve(profile.resourceOverlayName())
                .toAbsolutePath().normalize();
        return new ProfileInputs(
                firstReadableProfileInput(searchDirectories, profile.patchBundleName(), patchFallback),
                profile.patchBundleSha256(),
                firstReadableProfileInput(searchDirectories, profile.resourceOverlayName(), overlayFallback),
                profile.resourceOverlaySha256());
    }

    private static String[] contentProfileNamesForMode(String selectedMode) {
        if (BUILD_IWA.equals(selectedMode)) return IWA_CONTENT_PROFILE_NAMES.clone();
        if (BUILD_STANDALONE.equals(selectedMode)) return STANDALONE_CONTENT_PROFILE_NAMES.clone();
        return SOURCE_CONTENT_PROFILE_NAMES.clone();
    }

    private static boolean profileAvailableForMode(String profileName, String selectedMode) {
        return profileName != null && Arrays.asList(contentProfileNamesForMode(selectedMode)).contains(profileName);
    }

    private static String profileAfterModeChange(String profileName, String selectedMode) {
        return profileAvailableForMode(profileName, selectedMode) ? profileName : NORMAL_CONTENT_PROFILE.name();
    }

    private void updateAvailableContentProfiles() {
        String selectedBefore = (String)contentProfile.getSelectedItem();
        String selectedAfter = profileAfterModeChange(selectedBefore, (String)mode.getSelectedItem());
        String[] names = contentProfileNamesForMode((String)mode.getSelectedItem());
        boolean modelChanged = contentProfile.getItemCount() != names.length;
        if (!modelChanged) {
            for (int index = 0; index < names.length; index++) {
                if (!names[index].equals(contentProfile.getItemAt(index))) {
                    modelChanged = true;
                    break;
                }
            }
        }
        if (modelChanged) {
            javax.swing.DefaultComboBoxModel<String> next = new javax.swing.DefaultComboBoxModel<>(names);
            next.setSelectedItem(selectedAfter);
            contentProfile.setModel(next);
        }
        boolean profileWasRestricted = !selectedAfter.equals(selectedBefore);
        if (profileWasRestricted) {
            contentProfile.setSelectedItem(selectedAfter);
            applyContentProfile();
        }
    }

    private static Path firstReadableProfileInput(List<Path> searchDirectories, String fileName, Path fallback) {
        for (Path directory : searchDirectories) {
            Path candidate = directory.resolve(fileName);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return fallback;
    }

    private void discoverInstalledTools() {
        discoverInstalledTools(false);
    }

    private void discoverInstalledTools(boolean replaceInvalidPaths) {
        Map<String, String> currentPaths = new LinkedHashMap<>();
        for (String key : List.of("java17", "java25", "node", "npm", "ffmpeg")) {
            currentPaths.put(key, fields.get(key).getText().trim());
        }
        toolDiscoveryPending = true;
        updateActionState();
        new SwingWorker<ToolDiscovery, Void>() {
            @Override
            protected ToolDiscovery doInBackground() {
                Map<String, Path> found = discoverBundledTools();
                Set<String> invalidCurrentPaths = new LinkedHashSet<>();
                for (String key : List.of("java17", "java25", "node", "npm", "ffmpeg")) {
                    String current = currentPaths.get(key);
                    if (current != null && !current.isBlank()) {
                        try {
                            if (!validToolCandidate(key, Path.of(current))) invalidCurrentPaths.add(key);
                            else continue;
                        } catch (RuntimeException ex) {
                            invalidCurrentPaths.add(key);
                        }
                    }
                    if (found.containsKey(key)) continue;
                    Path discovered = switch (key) {
                        case "java17" -> findJavaMajor("17");
                        case "java25" -> findJavaMajor("25");
                        case "node" -> findNode();
                        case "npm" -> findNpm();
                        case "ffmpeg" -> findFfmpeg();
                        default -> null;
                    };
                    if (discovered != null) found.put(key, discovered);
                }
                return new ToolDiscovery(found, invalidCurrentPaths);
            }

            @Override
            protected void done() {
                try {
                    ToolDiscovery discovery = get();
                    for (Map.Entry<String, Path> entry : discovery.found.entrySet()) {
                        if (!hasValue(entry.getKey())
                                || (replaceInvalidPaths && discovery.invalidCurrentPaths.contains(entry.getKey()))) {
                            fields.get(entry.getKey()).setText(entry.getValue().toString());
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    log.append("GUI: local tool discovery failed: " + rootMessage(ex) + "\n");
                } finally {
                    toolDiscoveryPending = false;
                }
                updateReadiness();
            }
        }.execute();
    }

    private void discoverBuildMemory() {
        new SwingWorker<BuildMemoryBudget, Void>() {
            @Override
            protected BuildMemoryBudget doInBackground() {
                return BuildMemoryBudget.detect();
            }

            @Override
            protected void done() {
                try {
                    buildMemoryBudget = get();
                    updateBuildMemorySummary();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    buildMemorySummary.setText("Build memory: checked by the CLI before building.");
                } catch (ExecutionException ex) {
                    buildMemorySummary.setText("Build memory: checked by the CLI before building.");
                }
            }
        }.execute();
    }

    private void updateBuildMemorySummary() {
        if (buildMemoryBudget == null) return;
        boolean webBuild = BUILD_STANDALONE.equals(mode.getSelectedItem())
                || BUILD_IWA.equals(mode.getSelectedItem());
        buildMemorySummary.setText(webBuild ? buildMemoryBudget.summary() : buildMemoryBudget.sourceSummary());
    }

    private static boolean validToolCandidate(String key, Path candidate) {
        return switch (key) {
            case "java17" -> isExecutable(candidate) && versionMatches(candidate, "-version", "17");
            case "java25" -> isExecutable(candidate) && versionMatches(candidate, "-version", "25");
            case "node" -> isExecutable(candidate) && versionMatches(candidate, "--version", "20+");
            case "ffmpeg" -> isExecutable(candidate) && hasNoSymlinkComponents(candidate)
                    && versionMatches(candidate, "-version", "6.1.1");
            case "npm" -> npmCliPath(candidate) != null;
            default -> false;
        };
    }

    private record ToolDiscovery(Map<String, Path> found, Set<String> invalidCurrentPaths) {
    }

    private static Map<String, Path> discoverBundledTools() {
        Map<String, Path> found = new LinkedHashMap<>();
        Path launchDirectory = codeSource().getParent();
        if (launchDirectory == null) return found;
        Path manifest = launchDirectory.resolve("toolchain.properties");
        if (!Files.isRegularFile(manifest) || !Files.isReadable(manifest)) return found;
        Properties properties = new Properties();
        try (InputStreamReader input = new InputStreamReader(Files.newInputStream(manifest), StandardCharsets.UTF_8)) {
            properties.load(input);
            for (String key : List.of("java17", "java25", "node", "npm", "ffmpeg")) {
                String value = properties.getProperty(key);
                if (value == null || value.isBlank()) continue;
                Path candidate = Path.of(value).toAbsolutePath().normalize();
                boolean accepted = switch (key) {
                    case "java17" -> isExecutable(candidate) && versionMatches(candidate, "-version", "17");
                    case "java25" -> isExecutable(candidate) && versionMatches(candidate, "-version", "25");
                    case "node" -> isExecutable(candidate) && versionMatches(candidate, "--version", "20+");
                    case "ffmpeg" -> isExecutable(candidate) && hasNoSymlinkComponents(candidate)
                            && versionMatches(candidate, "-version", "6.1.1");
                    case "npm" -> npmCliPath(candidate) != null;
                    default -> false;
                };
                if (accepted) found.put(key, candidate);
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // An incomplete local install is skipped; normal tool discovery still runs.
        }
        return found;
    }

    private static Path findJavaMajor(String major) {
        for (Path candidate : javaCandidates()) {
            if (isExecutable(candidate) && versionMatches(candidate, "-version", major)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path findNode() {
        for (Path candidate : namedToolCandidates("node", "node.exe", "NODE_HOME")) {
            if (isExecutable(candidate) && versionMatches(candidate, "--version", "20+")) {
                return candidate;
            }
        }
        return null;
    }

    private static Path findNpm() {
        for (Path candidate : namedToolCandidates("npm", "npm.cmd", "NPM_CLI")) {
            Path cli = npmCliPath(candidate);
            if (cli != null) return cli;
        }
        for (Path node : namedToolCandidates("node", "node.exe", "NODE_HOME")) {
            Path bin = node.getParent();
            if (bin == null) continue;
            Path prefix = bin.getParent();
            if (prefix == null) continue;
            for (Path candidate : List.of(prefix.resolve("lib/node_modules/npm/bin/npm-cli.js"),
                    prefix.resolve("node_modules/npm/bin/npm-cli.js"))) {
                Path cli = npmCliPath(candidate);
                if (cli != null) return cli;
            }
        }
        return null;
    }

    private static Path findFfmpeg() {
        for (Path candidate : namedToolCandidates("ffmpeg", "ffmpeg.exe", "FFMPEG_HOME")) {
            if (isExecutable(candidate) && hasNoSymlinkComponents(candidate)
                    && versionMatches(candidate, "-version", "6.1.1")) {
                return candidate;
            }
        }
        return null;
    }

    private static Path npmCliPath(Path candidate) {
        try {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                Path resolved = candidate.toRealPath();
                if (resolved.getFileName().toString().equalsIgnoreCase("npm-cli.js")) return resolved;
            }
        } catch (IOException ignored) {
            // An incomplete installation is simply not a discovery candidate.
        }
        return null;
    }

    private static List<Path> javaCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        addJavaHome(candidates, System.getenv("JAVA_HOME"));
        addJavaHome(candidates, System.getenv("JDK_HOME"));
        addJavaHome(candidates, System.getProperty("java.home"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        for (Path path : pathEntries()) {
            candidates.add(path.resolve(isWindows(os) ? "java.exe" : "java"));
        }
        if (isWindows(os)) {
            addJavaInstalls(candidates, envPath("ProgramFiles", "Java"));
            addJavaInstalls(candidates, envPath("ProgramFiles", "Eclipse Adoptium"));
            addJavaInstalls(candidates, envPath("LOCALAPPDATA", "Programs", "Eclipse Adoptium"));
        } else if (os.contains("mac")) {
            addMacJavaInstalls(candidates, Path.of("/Library/Java/JavaVirtualMachines"));
            addMacJavaInstalls(candidates, Path.of(System.getProperty("user.home", "."),
                    "Library/Java/JavaVirtualMachines"));
        } else {
            addJavaInstalls(candidates, Path.of("/usr/lib/jvm"));
            addJavaInstalls(candidates, Path.of("/opt/java"));
        }
        return new ArrayList<>(candidates);
    }

    private static List<Path> namedToolCandidates(String unixName, String windowsName, String homeVariable) {
        Set<Path> candidates = new LinkedHashSet<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String named = isWindows(os) ? windowsName : unixName;
        String configuredHome = System.getenv(homeVariable);
        if (configuredHome != null && !configuredHome.isBlank()) {
            Path home = Path.of(configuredHome);
            candidates.add(Files.isDirectory(home) ? home.resolve(isWindows(os) ? "bin" : "bin").resolve(named) : home);
        }
        for (Path path : pathEntries()) candidates.add(path.resolve(named));
        if (!isWindows(os)) {
            candidates.add(Path.of("/usr/bin", unixName));
            candidates.add(Path.of("/usr/local/bin", unixName));
            candidates.add(Path.of("/opt/homebrew/bin", unixName));
        }
        return new ArrayList<>(candidates);
    }

    private static boolean versionMatches(Path executable, String argument, String requested) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), argument).redirectErrorStream(true);
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            process = builder.start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) return false;
            if ("20+".equals(requested)) return version.matches("(?s).*v?(?:2[0-9]|[3-9][0-9])\\..*");
            if ("6.1.1".equals(requested)) return version.startsWith("ffmpeg version 6.1.1");
            Pattern javaVersion = Pattern.compile("(?i)(?:version\\s+)?[\\\"']?(?:1\\.)?(\\d+)(?:[._+\\\"']|\\s|$)");
            Matcher matcher = javaVersion.matcher(version);
            while (matcher.find()) {
                if (requested.equals(matcher.group(1))) return true;
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
        return false;
    }

    private static void addJavaHome(Set<Path> candidates, String homeValue) {
        if (homeValue == null || homeValue.isBlank()) return;
        Path home = Path.of(homeValue);
        String executable = isWindows(System.getProperty("os.name", "").toLowerCase(Locale.ROOT))
                ? "java.exe" : "java";
        candidates.add(home.resolve("bin").resolve(executable));
        candidates.add(home.resolve("Contents/Home/bin").resolve(executable));
    }

    private static void addJavaInstalls(Set<Path> candidates, Path root) {
        if (!Files.isDirectory(root)) return;
        try (var entries = Files.list(root)) {
            entries.limit(100).forEach(entry -> addJavaHome(candidates, entry.toString()));
        } catch (IOException ignored) {
            // Unreadable optional installation roots are ignored.
        }
    }

    private static void addMacJavaInstalls(Set<Path> candidates, Path root) {
        if (!Files.isDirectory(root)) return;
        try (var entries = Files.list(root)) {
            entries.limit(100).forEach(entry -> addJavaHome(candidates, entry.resolve("Contents/Home").toString()));
        } catch (IOException ignored) {
            // Unreadable optional installation roots are ignored.
        }
    }

    private static List<Path> pathEntries() {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) return List.of();
        List<Path> entries = new ArrayList<>();
        for (String entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) entries.add(Path.of(entry));
        }
        return entries;
    }

    private static Path envPath(String... parts) {
        String root = System.getenv(parts[0]);
        if (root == null || root.isBlank()) return Path.of(".", "__missing__");
        Path result = Path.of(root);
        for (int i = 1; i < parts.length; i++) result = result.resolve(parts[i]);
        return result;
    }

    private static boolean isWindows(String os) {
        return os.contains("win");
    }

    private static boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isReadable(path) && Files.isExecutable(path);
    }

    private static boolean hasNoSymlinkComponents(Path path) {
        try {
            rejectSymlinkComponents(path);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private JPanel primaryPath(String label, String key, boolean directory) {
        JTextField field = new JTextField();
        field.setColumns(14);
        fields.put(key, field);

        JButton browse = new JButton("Browse…");
        browse.addActionListener(event -> choosePath(field, directory));
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.add(field, BorderLayout.CENTER);
        controls.add(browse, BorderLayout.EAST);

        JPanel pathGroup = new JPanel(new BorderLayout(4, 3));
        pathGroup.setBorder(BorderFactory.createEmptyBorder(3, 0, 5, 0));
        pathGroup.add(new JLabel(label), BorderLayout.NORTH);
        pathGroup.add(controls, BorderLayout.CENTER);
        pathGroup.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        pathGroup.setMaximumSize(new Dimension(Integer.MAX_VALUE, pathGroup.getPreferredSize().height));
        return pathGroup;
    }

    private void addPath(JPanel panel, String label, String key, boolean directory) {
        JTextField field = new JTextField();
        field.setColumns(18);
        fields.put(key, field);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(event -> choosePath(field, directory));
        addRow(panel, new JLabel(label), field, true, browse);
    }

    private void addText(JPanel panel, String label, String key, String tooltip) {
        JTextField field = new JTextField();
        field.setColumns(18);
        field.setToolTipText(tooltip);
        fields.put(key, field);
        addRow(panel, new JLabel(label), field, true, null);
    }

    private void addRow(JPanel panel, java.awt.Component label, JTextField field, boolean fill) {
        addRow(panel, label, field, fill, null);
    }

    private void addRow(JPanel panel, java.awt.Component label, JTextField field, boolean fill, JButton button) {
        int row = panel.getComponentCount();
        GridBagConstraints left = constraints(0, row, 0.0, GridBagConstraints.NONE);
        left.anchor = GridBagConstraints.WEST;
        panel.add(label, left);
        if (field != null) {
            GridBagConstraints middle = constraints(1, row, 1.0, fill
                    ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE);
            panel.add(field, middle);
        }
        if (button != null) {
            GridBagConstraints right = constraints(2, row, 0.0, GridBagConstraints.NONE);
            panel.add(button, right);
        }
    }

    private void addSeparator(JPanel panel) {
        GridBagConstraints separator = constraints(0, panel.getComponentCount(), 1.0, GridBagConstraints.HORIZONTAL);
        separator.gridwidth = 3;
        separator.insets = new Insets(8, 0, 8, 0);
        panel.add(new JSeparator(), separator);
    }

    private static GridBagConstraints constraints(int x, int y, double weight, int fill) {
        GridBagConstraints result = new GridBagConstraints();
        result.gridx = x;
        result.gridy = y;
        result.weightx = weight;
        result.fill = fill;
        result.insets = new Insets(3, 4, 3, 4);
        return result;
    }

    private void choosePath(JTextField field, boolean directory) {
        boolean outputDirectory = directory && field == fields.get("output");
        Path currentPath = null;
        try {
            String currentText = field.getText().trim();
            if (!currentText.isEmpty()) currentPath = Path.of(currentText).toAbsolutePath().normalize();
        } catch (InvalidPathException ignored) {
            // Leave the chooser at the working directory when the field is not a valid path.
        }
        Path chooserStart = directory ? directoryChooserStart(currentPath)
                : currentPath != null && Files.isDirectory(currentPath) ? currentPath : null;
        JFileChooser chooser = chooserStart == null ? new JFileChooser() : new JFileChooser(chooserStart.toFile());
        chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        JTextField newDirectoryName = null;
        if (directory) {
            chooser.setDialogTitle(outputDirectory ? "Choose project output folder" : "Choose folder");
            chooser.setApproveButtonText("Choose Folder");
        }
        if (outputDirectory) {
            newDirectoryName = new JTextField(18);
            if (currentPath != null && !Files.isDirectory(currentPath) && currentPath.getFileName() != null) {
                newDirectoryName.setText(currentPath.getFileName().toString());
            }
            JTextField folderNameField = newDirectoryName;
            JButton useNewPath = new JButton("Use New Path");
            useNewPath.addActionListener(event -> {
                try {
                    chooser.setSelectedFile(newDirectoryPath(
                            chooser.getCurrentDirectory().toPath(), folderNameField.getText()).toFile());
                    chooser.approveSelection();
                } catch (IllegalArgumentException ex) {
                    showError(ex.getMessage());
                }
            });
            JPanel accessory = new JPanel(new BorderLayout(4, 4));
            accessory.add(new JLabel("New folder name (optional):"), BorderLayout.NORTH);
            accessory.add(newDirectoryName, BorderLayout.CENTER);
            accessory.add(useNewPath, BorderLayout.SOUTH);
            accessory.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            chooser.setAccessory(accessory);
        }
        if (field == fields.get("jar")) {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Minecraft client JAR (*.jar)", "jar"));
        } else if (field == fields.get("iwaOutput")) {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Signed Isolated Web App (*.swbn)", "swbn"));
        } else if (field == fields.get("iwaKey")) {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Local IWA signing key (*.pem)", "pem"));
        } else if (field == fields.get("wispcraftScript")) {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Compiled Wispcraft script (index.js)", "js"));
        }
        int result = directory ? chooser.showDialog(frame, "Choose Folder") : chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            Path selectedPath = chooser.getSelectedFile() == null ? null
                    : chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (selectedPath == null) {
                showError("Choose a folder or enter a new folder name.");
                return;
            }
            field.setText(selectedPath.toString());
            if (field == fields.get("skeleton")) {
                fields.get("skeletonSha").setText(ProjectSkeleton.acceptedArchiveSha256());
            } else if (field == fields.get("resourceOverlay")) {
                fields.get("resourceSha").setText(contentProfileSpec(
                        (String)contentProfile.getSelectedItem()).resourceOverlaySha256());
            }
        }
    }

    private static Path newDirectoryPath(Path parent, String folderName) {
        String trimmed = folderName.trim();
        Path namePath;
        try {
            namePath = Path.of(trimmed);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Enter a valid single folder name.", ex);
        }
        if (trimmed.isEmpty() || namePath.isAbsolute() || namePath.getNameCount() != 1
                || trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException("Enter a single folder name.");
        }
        return parent.resolve(namePath).toAbsolutePath().normalize();
    }

    private static Path directoryChooserStart(Path selectedPath) {
        Path candidate = selectedPath == null ? Path.of("").toAbsolutePath().normalize() : selectedPath;
        while (candidate != null && !Files.isDirectory(candidate)) candidate = candidate.getParent();
        return candidate == null ? Path.of("").toAbsolutePath().normalize() : candidate;
    }

    private void updateModeHint() {
        updateAvailableContentProfiles();
        if (BUILD_STANDALONE.equals(mode.getSelectedItem())) {
            queueProjectProbe();
            setWrappedText(modeHint, "Builds source and HTML together. Choose an existing patcher project to reuse its source automatically; a new folder starts from the JAR.");
            run.setText("Build HTML");
        } else if (BUILD_IWA.equals(mode.getSelectedItem())) {
            setWrappedText(modeHint, "Build a signed Isolated Web App locally with the Normal content profile only. Keep the signing key for updates; optionally save browser-test HTML and music from the same compile.");
            run.setText("Build .swbn");
        } else {
            setWrappedText(modeHint, "Create patched source from the official JAR.");
            run.setText("Create project");
        }
        boolean standalone = BUILD_STANDALONE.equals(mode.getSelectedItem());
        boolean iwa = BUILD_IWA.equals(mode.getSelectedItem());
        boolean webBuild = standalone || iwa;
        updateBuildMemorySummary();
        reuseExistingProject.setVisible(standalone);
        standaloneOutputPanel.setVisible(webBuild);
        iwaOutputPanel.setVisible(iwa);
        webOutputPanel.setVisible(webBuild);
        JPanel htmlPathGroup = (JPanel) standaloneOutputPanel.getComponent(0);
        ((JLabel) htmlPathGroup.getComponent(0)).setText(
                standalone ? "HTML file" : "Browser-test HTML (optional)");
        modOptionsPanel.setVisible(standalone);
        standaloneFields.setVisible(webBuild);
        audioPreparationPanel.setVisible(webBuild);
        includeWispcraft.setEnabled(standalone);
        embedMusicInHtml.setVisible(standalone);
        musicHelp.setVisible(standalone);
        wispcraftScriptPanel.setVisible(standalone && includeWispcraft.isSelected());
        updateIwaKeyInfo();
        updateReadiness();
        frame.revalidate();
    }

    private void updateIwaKeyInfo() {
        iwaKeyInfo.setText(hasValue("iwaKey")
                ? "Keep this private key to preserve the app's identity when rebuilding."
                : "If blank, the CLI stores the key under the selected project folder.");
    }

    private void updateReadiness() {
        if (readiness == null || !fields.containsKey("jar")) {
            return;
        }
        boolean standalone = BUILD_STANDALONE.equals(mode.getSelectedItem());
        boolean iwa = BUILD_IWA.equals(mode.getSelectedItem());
        boolean webBuild = standalone || iwa;
        String message;
        if (!readableFile("jar")) {
            message = hasValue("jar")
                    ? "Choose a readable Minecraft 26.2 JAR."
                    : "Choose your Minecraft 26.2 JAR.";
        } else if (!readableFile("patchBundle")) {
            message = "Build files are missing. Open the complete Setup JAR and choose a new setup folder.";
        } else if (!readableFile("vineflower")) {
            message = "Decompiler missing. Open the complete Setup JAR and choose a new setup folder.";
        } else if (!toolVersionMatches("java17", "-version", "17")) {
            message = "Java 17 missing or wrong version. Install build tools.";
        } else if (!usableProjectOutput()) {
            message = reusableProjectSelected()
                    ? "Choose a readable existing project folder; the CLI will verify it before reuse."
                    : "Choose a new or empty folder, or an existing project made by this patcher.";
        } else if (hasValue("skeleton") && !readableFile("skeleton")) {
            message = "Project files are unreadable. Build will look for the bundled copy.";
        } else if (hasValue("resourceOverlay") && !readableFile("resourceOverlay")) {
            message = "Resource files are unreadable. Build will look for the bundled copy.";
        } else if (hasValue("resourceOverlay") != hasValue("externalRoot")) {
            message = "Official resources will be downloaded automatically when you build.";
        } else if (hasValue("externalRoot") && !directoryAvailable("externalRoot")) {
            message = "Resources folder is unreadable. Check Advanced.";
        } else if (webBuild && (!toolVersionMatches("java25", "-version", "25")
                || !toolVersionMatches("node", "--version", "20+") || !validNpmCli("npm"))) {
            message = "Web build needs Java 25, Node and npm. Install build tools.";
        } else if (webBuild && (!readableFile("skeleton") || !readableFile("resourceOverlay")
                || !directoryAvailable("externalRoot"))) {
            message = "Web build needs the complete Setup JAR. Build will recheck the installed files.";
        } else if (webBuild && (!readableFile("sounds") || !validHash("soundsSha"))) {
            message = shouldAutoPrepareAudio(true, hasValue("sounds"), audioOutputTargetsAbsent())
                    ? "Game audio will be prepared automatically when you build."
                    : "Audio setup is incomplete. Open the complete Setup JAR and choose a new setup folder.";
        } else if (webBuild && !packagedMusicAvailable()) {
            message = "Music files are missing. Reinstall the complete Setup JAR into a new folder.";
        } else if (standalone && includeWispcraft.isSelected() && !validWispcraftScript()) {
            message = "Choose the local compiled Wispcraft dist/index.js file in Optional mod.";
        } else if (standalone && !embedMusicInHtml.isSelected() && musicPackOutputExists()) {
            message = "The sibling music resource-pack ZIP already exists. Choose another HTML filename.";
        } else if (standalone && !usableOutput("standaloneOutput", false)) {
            message = "Choose another HTML filename.";
        } else if (iwa && (!usableOutput("iwaOutput", false)
                || !fields.get("iwaOutput").getText().trim().toLowerCase(Locale.ROOT).endsWith(".swbn"))) {
            message = "Choose a new .swbn output filename.";
        } else if (iwa && hasValue("standaloneOutput") && !usableOutput("standaloneOutput", false)) {
            message = "Choose another browser-test HTML filename.";
        } else if (iwa && hasValue("standaloneOutput") && musicPackOutputExists()) {
            message = "The music resource-pack ZIP already exists beside that HTML. Choose another filename.";
        } else if (iwa && iwaOutputSidecarExists()) {
            message = "An IWA sidecar already exists. Choose another .swbn filename.";
        } else if (iwa && !iwaKeyUsable()) {
            message = "Choose a new key path or an existing readable signing key.";
        } else {
            message = reusableProjectSelected()
                    ? "Ready. The CLI will verify the existing project before building."
                    : "Ready. Inputs will be checked before patching.";
        }
        updateIwaKeyInfo();
        setWrappedText(readiness, message);
        updateActionState();
        Path bootstrap = availableBootstrapScript();
        if (bootstrap == null) {
            setupTools.setToolTipText("Automatic tool setup is available on supported Linux x86_64 and Windows x64 packages.");
        } else {
            setupTools.setToolTipText("Downloads verified Java 17, Java 25, and Node.js tools into "
                    + codeSource().getParent() + ". It does not download the game JAR or media.");
        }
    }

    private void updateActionState() {
        boolean patchActive = worker != null && !worker.isDone();
        boolean audioActive = audioTaskActive.get() || (audioWorker != null && !audioWorker.isDone());
        boolean setupActive = (setupWorker != null && !setupWorker.isDone())
                || (resourceWorker != null && !resourceWorker.isDone()) || audioActive;
        ActionState state = actionState(patchActive, setupActive, toolDiscoveryPending,
                runLocked, selectedOutputsAvailable(), availableBootstrapScript() != null, cancelPending);
        run.setEnabled(state.canRun);
        setupTools.setEnabled(state.canSetup);
        getOfficialResources.setEnabled(!patchActive && !setupActive && !audioActive && !runLocked);
        getGameAudio.setEnabled(isWebBuildSelected() && !patchActive && !setupActive && !audioActive
                && !runLocked && !toolDiscoveryPending && audioOutputTargetsAbsent());
        cancel.setEnabled(state.canCancel || (audioActive && !audioCancelPending));
    }

    private boolean selectedOutputsAvailable() {
        if (!usableProjectOutput()) return false;
        if (BUILD_STANDALONE.equals(mode.getSelectedItem())) {
            return usableOutput("standaloneOutput", false)
                    && (embedMusicInHtml.isSelected() || !musicPackOutputExists());
        }
        if (BUILD_IWA.equals(mode.getSelectedItem())) {
            return usableOutput("iwaOutput", false)
                    && !iwaOutputSidecarExists()
                    && iwaKeyUsable()
                    && (!hasValue("standaloneOutput")
                            || (usableOutput("standaloneOutput", false) && !musicPackOutputExists()));
        }
        return true;
    }

    private static ActionState actionState(boolean patchActive, boolean setupActive, boolean discoveringTools,
            boolean processLocked, boolean outputsAvailable, boolean bootstrapAvailable, boolean cancelPending) {
        boolean idle = !patchActive && !setupActive && !discoveringTools && !processLocked;
        return new ActionState(idle && outputsAvailable, idle && bootstrapAvailable,
                patchActive && !cancelPending);
    }

    private record ActionState(boolean canRun, boolean canSetup, boolean canCancel) {
    }

    private boolean readableFile(String key) {
        if (!hasValue(key)) return false;
        try {
            Path candidate = path(key);
            return Files.isRegularFile(candidate) && Files.isReadable(candidate);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean executableFile(String key) {
        if (!hasValue(key)) return false;
        try {
            Path candidate = path(key);
            return Files.isRegularFile(candidate) && Files.isReadable(candidate) && Files.isExecutable(candidate);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean toolVersionMatches(String key, String versionArgument, String expected) {
        if (!executableFile(key)) return false;
        try {
            Path candidate = path(key);
            if ("ffmpeg".equals(key) && !hasNoSymlinkComponents(candidate)) return false;
            String cacheKey = key + "|" + candidate.toString() + "|" + versionArgument + "|" + expected;
            Boolean cached = toolVersionCache.get(cacheKey);
            if (cached != null) return cached;
            boolean matches = versionMatches(candidate, versionArgument, expected);
            toolVersionCache.put(cacheKey, matches);
            return matches;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean validNpmCli(String key) {
        if (!readableFile(key)) return false;
        try {
            Path candidate = npmCliPath(path(key));
            if (candidate == null || !executableFile("node")) return false;
            String cacheKey = "npm|" + path("node") + "|" + candidate;
            Boolean cached = toolVersionCache.get(cacheKey);
            if (cached != null) return cached;
            Process process = null;
            boolean matches = false;
            try {
                ProcessBuilder builder = new ProcessBuilder(path("node").toString(), candidate.toString(), "--version")
                        .redirectErrorStream(true);
                builder.environment().remove("JAVA_TOOL_OPTIONS");
                builder.environment().remove("JDK_JAVA_OPTIONS");
                builder.environment().remove("_JAVA_OPTIONS");
                process = builder.start();
                if (process.waitFor(3, TimeUnit.SECONDS)) {
                    String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    matches = process.exitValue() == 0 && version.matches("(?s)^(?:[1-9][0-9]*)\\..*");
                } else {
                    process.destroyForcibly();
                }
            } catch (IOException ex) {
                matches = false;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                matches = false;
            } finally {
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
            toolVersionCache.put(cacheKey, matches);
            return matches;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean directoryAvailable(String key) {
        if (!hasValue(key)) return false;
        try {
            return Files.isDirectory(path(key)) && Files.isReadable(path(key));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean validHash(String key) {
        return hasValue(key) && fields.get(key).getText().trim().matches("[0-9a-f]{64}");
    }

    private boolean usableOutput(String key, boolean directory) {
        if (!hasValue(key)) return false;
        try {
            Path candidate = path(key);
            boolean targetAvailable = !Files.exists(candidate)
                    || (directory && Files.isDirectory(candidate) && !hasChildren(candidate));
            if (!targetAvailable) return false;
            String marker = directory ? ".staging-" : ".partial-";
            return !hasPrefixedSibling(candidate, "." + candidate.getFileName() + marker);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean reusableProjectSelected() {
        return shouldReuseProject((String)mode.getSelectedItem(), reuseExistingProject.isSelected(),
                recognizedProjectText, fields.get("output").getText().trim());
    }

    private static boolean shouldReuseProject(String selectedMode, boolean forced,
            String recognizedPath, String currentPath) {
        return BUILD_STANDALONE.equals(selectedMode)
                && (forced || recognizedPath != null && recognizedPath.equals(currentPath));
    }

    private boolean usableProjectOutput() {
        if (!reusableProjectSelected()) return usableOutput("output", true);
        if (!hasValue("output")) return false;
        try {
            Path candidate = path("output");
            return Files.isDirectory(candidate) && Files.isReadable(candidate);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean hasPrefixedSibling(Path target, String prefix) {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) return false;
        try (var entries = Files.list(parent)) {
            return entries.anyMatch(entry -> entry.getFileName().toString().startsWith(prefix));
        } catch (IOException ex) {
            return true;
        }
    }

    private Path availableBootstrapScript() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64) return null;
        String relative;
        if (isWindows(os)) {
            relative = "windows/bootstrap-windows-x86_64.ps1";
        } else if (os.contains("linux")) {
            relative = "bootstrap-linux-x86_64.sh";
        } else {
            return null;
        }
        Path appDirectory = codeSource().getParent();
        if (appDirectory == null) return null;
        for (Path candidateRoot : List.of(appDirectory, appDirectory.getParent() == null
                ? appDirectory : appDirectory.getParent())) {
            Path candidate = candidateRoot.resolve("bootstrap").resolve(relative).normalize();
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) return candidate;
        }
        return null;
    }

    private void setupTools() {
        if ((setupWorker != null && !setupWorker.isDone()) || (worker != null && !worker.isDone())
                || (resourceWorker != null && !resourceWorker.isDone())
                || audioTaskActive.get() || toolDiscoveryPending || runLocked) return;
        Path script = availableBootstrapScript();
        if (script == null) {
            updateReadiness();
            return;
        }
        Path appDirectory = codeSource().getParent();
        List<String> command = new ArrayList<>();
        if (isWindows(System.getProperty("os.name", "").toLowerCase(Locale.ROOT))) {
            command.addAll(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString(), "-AppDir", appDirectory.toString()));
        } else {
            command.addAll(List.of("sh", script.toString(), "--app-dir", appDirectory.toString()));
        }
        log.setText("");
        handleOutput("Installing Java and Node in " + appDirectory);
        progress.setIndeterminate(true);
        progress.setString("Installing…");
        progressLabel.setText("Installing build tools...");
        setupWorker = new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() throws Exception {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) publish(line);
                }
                return process.waitFor();
            }

            @Override
            protected void process(List<String> lines) {
                handleOutput(lines);
            }

            @Override
            protected void done() {
                progress.setIndeterminate(false);
                progress.setString(null);
                progress.setValue(0);
                try {
                    int exit = get();
                    if (exit == 0) {
                        progressLabel.setText("Build tools installed.");
                        handleOutput("Tool setup done.");
                        toolVersionCache.clear();
                        discoverInstalledTools(true);
                    } else {
                        progressLabel.setText("Tool setup failed (code " + exit + "). See Log.");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    progressLabel.setText("Tool setup stopped.");
                } catch (ExecutionException ex) {
                    String detail = rootMessage(ex);
                    progressLabel.setText("Tool setup failed: " + detail);
                    handleOutput("ERROR: " + detail);
                }
                updateReadiness();
            }
        };
        setupWorker.execute();
        updateActionState();
    }

    private void getOfficialResources() {
        if ((resourceWorker != null && !resourceWorker.isDone())
                || (setupWorker != null && !setupWorker.isDone())
                || (worker != null && !worker.isDone())
                || audioTaskActive.get() || runLocked) return;
        Path appDirectory = codeSource().getParent();
        if (appDirectory == null) {
            runAfterResources = false;
            showError("Could not locate the patcher folder.");
            return;
        }
        Path delta = appDirectory.resolve("inputs/zh-cn-delta-26.2.json");
        if (!Files.isRegularFile(delta)) {
            runAfterResources = false;
            showError("Chinese catalog patch is missing from this patcher kit.");
            return;
        }
        Path output = appDirectory.resolve("inputs/resources");
        progress.setIndeterminate(true);
        progress.setString("Getting resources…");
        progressLabel.setText("Getting official resources...");
        resourceWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (Files.exists(output)) OfficialResources.verifyExisting(output);
                else OfficialResources.stage(output, delta, null, null);
                return null;
            }

            @Override
            protected void done() {
                progress.setIndeterminate(false);
                progress.setString(null);
                progress.setValue(0);
                try {
                    get();
                    fields.get("externalRoot").setText(output.toString());
                    progressLabel.setText("Resources ready.");
                    handleOutput("Official resources ready in " + output);
                    if (runAfterResources) {
                        runAfterResources = false;
                        javax.swing.SwingUtilities.invokeLater(GuiMain.this::startRun);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    progressLabel.setText("Resource download stopped.");
                    runAfterResources = false;
                } catch (ExecutionException error) {
                    String detail = rootMessage(error);
                    progressLabel.setText("Resources failed. See Log.");
                    handleOutput("ERROR: " + detail);
                    runAfterResources = false;
                }
                updateReadiness();
            }
        };
        resourceWorker.execute();
        updateActionState();
    }

    private void getGameAudio() {
        if ((audioWorker != null && !audioWorker.isDone())
                || (resourceWorker != null && !resourceWorker.isDone())
                || (setupWorker != null && !setupWorker.isDone())
                || audioTaskActive.get() || (worker != null && !worker.isDone())
                || runLocked || toolDiscoveryPending) {
            runAfterAudio = false;
            return;
        }

        Path appDirectory = codeSource().getParent();
        if (appDirectory == null) {
            runAfterAudio = false;
            showError("Could not locate the patcher folder.");
            return;
        }
        if (!toolVersionMatches("node", "--version", "20+")) {
            runAfterAudio = false;
            showError("Preparing game audio needs Node.js 20 or newer. Choose or install Node.js first.");
            return;
        }
        if (!toolVersionMatches("ffmpeg", "-version", "6.1.1")) {
            runAfterAudio = false;
            showError("Preparing game audio needs FFmpeg 6.1.1. Install it manually, then choose its executable under Advanced.");
            return;
        }
        if (!audioOutputTargetsAbsent()) {
            runAfterAudio = false;
            showError("The patcher inputs/sounds.epk, music.epk, or their SHA-256 pins already exist. They were preserved; choose an empty patcher kit for automatic audio preparation.");
            return;
        }

        progress.setValue(0);
        progress.setIndeterminate(true);
        progress.setString("Preparing audio…");
        audioStartedNanos = System.nanoTime();
        audioTaskActive.set(true);
        audioBackgroundStarted.set(false);
        audioBackgroundExited.set(false);
        audioCleanupConfirmed.set(true);
        audioCancelPending = false;
        progressLabel.setText("Preparing official game audio…");
        audioProgressHeartbeat.start();
        audioWorker = new SwingWorker<>() {
            @Override
            protected AudioPreparationResult doInBackground() throws Exception {
                audioBackgroundStarted.set(true);
                try {
                Path toolsDirectory = locateAudioToolsDirectory(appDirectory);
                Path node = checkedExecutable(path("node"), "Node.js 20+");
                Path ffmpeg = checkedExecutable(path("ffmpeg"), "FFmpeg 6.1.1");
                Path script = checkedAudioTool(toolsDirectory.resolve("node_audio_rebuild.mjs"));
                Path metadata = checkedAudioTool(toolsDirectory.resolve("audio-epk-metadata-26.2.json"));
                Path pako = checkedAudioTool(toolsDirectory.resolve("vendor/pako-2.1.0/pako.min.js"));
                if (!sha256(metadata).equals(AUDIO_METADATA_SHA256)) {
                    throw new IOException("packaged audio metadata does not match its pinned SHA-256");
                }
                if (!sha256(pako).equals("ede2693a4a6a5126b9d35669062b358ecab6ae7b9b86a1cf302feb45a8514907")) {
                    throw new IOException("packaged audio compressor does not match its pinned SHA-256");
                }
                if (!audioOutputTargetsAbsent()) {
                    throw new IOException("audio output names became occupied; existing files were preserved");
                }

                publish("Audio objects: checking the official index and verified cache.");
                OfficialAudioObjects.Result staged = OfficialAudioObjects.stage(appDirectory,
                        message -> publish("Audio objects: " + message));
                ensureAudioNotInterrupted();
                int verified = staged.cached() + staged.downloaded() + staged.cachedRace();
                if (staged.total() != OFFICIAL_AUDIO_OGG_COUNT || verified != OFFICIAL_AUDIO_OGG_COUNT) {
                    throw new IOException("official audio object staging was incomplete: verified " + verified
                            + "/" + OFFICIAL_AUDIO_OGG_COUNT);
                }
                Path cache = appDirectory.resolve("inputs/audio-cache").toAbsolutePath().normalize();
                Path index = cache.resolve("indexes/32.json");
                Path objects = staged.objectsPath().toAbsolutePath().normalize();
                if (!staged.indexPath().toAbsolutePath().normalize().equals(index)
                        || !objects.equals(cache.resolve("objects"))) {
                    throw new IOException("official audio staging returned paths outside the expected cache");
                }

                Path output = uniqueAudioRebuildOutput(cache);
                List<String> command = audioRebuildCommand(node, script, metadata, index, objects, ffmpeg,
                        output, pako);
                publish("Audio objects verified: " + verified + "/" + OFFICIAL_AUDIO_OGG_COUNT
                        + "; rebuilding pinned sounds and music EPKs.");
                long rebuildStartedMillis = System.currentTimeMillis();
                int exit = runAudioRebuild(command, message -> publish("Audio rebuild: " + message),
                        ignored -> { }, audioCleanupConfirmed);
                ensureAudioNotInterrupted();
                if (exit != 0) {
                    throw new IOException("Node audio rebuild exited with code " + exit);
                }
                Map<String, VerifiedAudioArchive> archives = verifyAudioRebuild(output, objects,
                        rebuildStartedMillis);
                ensureAudioNotInterrupted();
                if (!audioOutputTargetsAbsent()) {
                    throw new IOException("audio output names became occupied during rebuild; no existing file was replaced");
                }
                Path inputs = appDirectory.resolve("inputs").toAbsolutePath().normalize();
                Path sounds = inputs.resolve("sounds.epk");
                Path music = inputs.resolve("music.epk");
                Path soundsPin = sounds.resolveSibling("sounds.epk.sha256");
                Path musicPin = music.resolveSibling("music.epk.sha256");
                Path soundsPinSource = output.resolve("sounds.epk.sha256");
                Path musicPinSource = output.resolve("music.epk.sha256");
                writeAudioPin(soundsPinSource, archives.get("sounds").sha256());
                writeAudioPin(musicPinSource, archives.get("music").sha256());
                ensureAudioNotInterrupted();
                publishAudioFilesNoOverwrite(List.of(
                        new AudioPublishFile(output.resolve("sounds.epk"), sounds),
                        new AudioPublishFile(soundsPinSource, soundsPin),
                        new AudioPublishFile(output.resolve("music.epk"), music),
                        new AudioPublishFile(musicPinSource, musicPin)));
                publish("Verified audio published to " + inputs);
                return new AudioPreparationResult(sounds, archives.get("sounds").sha256(), music,
                        archives.get("music").sha256(), output, verified);
                } finally {
                    audioBackgroundExited.set(true);
                    javax.swing.SwingUtilities.invokeLater(GuiMain.this::audioBackgroundFinished);
                }
            }

            @Override
            protected void process(List<String> messages) {
                handleOutput(messages);
                refreshAudioProgressHeartbeat();
            }

            @Override
            protected void done() {
                boolean resumeRun = false;
                audioProgressHeartbeat.stop();
                progress.setIndeterminate(false);
                progress.setString(null);
                try {
                    AudioPreparationResult result = get();
                    audioTaskActive.set(false);
                    audioCancelPending = false;
                    boolean customAudioEdited = hasValue("sounds") || hasValue("soundsSha");
                    resumeRun = shouldResumeRunAfterAudio(runAfterAudio, true, isWebBuildSelected())
                            && audioCleanupConfirmed.get() && shouldAdoptPreparedAudio(customAudioEdited);
                    runAfterAudio = false;
                    if (shouldAdoptPreparedAudio(customAudioEdited)) {
                        fields.get("sounds").setText(result.sounds().toString());
                        fields.get("soundsSha").setText(result.soundsSha256());
                    }
                    progress.setValue(100);
                    progressLabel.setText("Game audio ready; official inputs verified.");
                    handleOutput("Game audio ready: " + result.verifiedObjects() + "/"
                            + OFFICIAL_AUDIO_OGG_COUNT + " official objects verified. Rebuild receipt and both archive pins passed.");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    runAfterAudio = false;
                    if (audioBackgroundExited.get() && audioCleanupConfirmed.get()) audioTaskActive.set(false);
                    progress.setValue(0);
                    progressLabel.setText("Audio preparation interrupted.");
                    handleOutput("ERROR: Audio preparation was interrupted.");
                } catch (ExecutionException ex) {
                    runAfterAudio = false;
                    if (audioBackgroundExited.get() && audioCleanupConfirmed.get()) audioTaskActive.set(false);
                    progress.setValue(0);
                    String detail = rootMessage(ex);
                    progressLabel.setText("Audio preparation failed. See Log.");
                    handleOutput("ERROR: " + detail);
                } catch (CancellationException ex) {
                    runAfterAudio = false;
                    progress.setValue(0);
                    if (!audioBackgroundStarted.get()) {
                        audioCleanupConfirmed.set(true);
                        audioBackgroundExited.set(true);
                        audioTaskActive.set(false);
                    } else if (audioBackgroundExited.get() && audioCleanupConfirmed.get()) {
                        audioTaskActive.set(false);
                    }
                    if (!audioCleanupConfirmed.get()) {
                        runLocked = true;
                        progressLabel.setText("Audio process cleanup could not be confirmed. Restart the patcher before continuing.");
                        handleOutput("ERROR: Audio process tree may still be running; restart the patcher before continuing.");
                    } else if (audioTaskActive.get()) {
                        progressLabel.setText("Stopping audio; verified cache is being preserved…");
                    } else {
                        progressLabel.setText("Audio preparation stopped; verified cache preserved.");
                        if (audioCancelPending) {
                            handleOutput("Audio preparation cancelled. Verified official objects and rebuild cache were preserved.");
                        }
                        audioCancelPending = false;
                    }
                }
                if (audioBackgroundStarted.get() && !audioCleanupConfirmed.get()) {
                    resumeRun = false;
                    runAfterAudio = false;
                    runLocked = true;
                    progressLabel.setText("Audio process cleanup could not be confirmed. Restart the patcher before continuing.");
                    handleOutput("ERROR: Audio process tree may still be running; restart the patcher before continuing.");
                }
                if (audioTaskActive.get()) updateActionState();
                else updateReadiness();
                if (resumeRun) javax.swing.SwingUtilities.invokeLater(GuiMain.this::startRun);
            }
        };
        audioWorker.execute();
        updateActionState();
    }

    private void refreshAudioProgressHeartbeat() {
        if (audioWorker == null || audioWorker.isDone()) return;
        long elapsed = Math.max(0L, (System.nanoTime() - audioStartedNanos) / 1_000_000_000L);
        long minutes = elapsed / 60L;
        long seconds = elapsed % 60L;
        progressLabel.setText("Preparing official audio  |  " + minutes + "m " + seconds + "s elapsed");
    }

    private void audioBackgroundFinished() {
        if (!audioBackgroundExited.get()) return;
        if (!audioCleanupConfirmed.get()) {
            runLocked = true;
            progressLabel.setText("Audio process cleanup could not be confirmed. Restart the patcher before continuing.");
            updateActionState();
            return;
        }
        if (!audioCancelPending && audioWorker != null && !audioWorker.isDone()) return;
        audioTaskActive.set(false);
        if (audioCancelPending && audioCleanupConfirmed.get()) {
            audioCancelPending = false;
            progress.setIndeterminate(false);
            progress.setString(null);
            progress.setValue(0);
            progressLabel.setText("Audio preparation stopped; verified cache preserved.");
            handleOutput("Audio preparation cancelled. Verified official objects and rebuild cache were preserved.");
        }
        updateReadiness();
    }

    private static void ensureAudioNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("audio preparation was interrupted");
    }

    private static Path locateAudioToolsDirectory(Path appDirectory) throws IOException {
        Path launch = appDirectory.toAbsolutePath().normalize();
        Path directory = launch.resolve("tools/audio");
        rejectSymlinkComponents(directory);
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve("node_audio_rebuild.mjs"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve("audio-epk-metadata-26.2.json"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve("vendor/pako-2.1.0/pako.min.js"), LinkOption.NOFOLLOW_LINKS)) {
            return directory;
        }
        throw new IOException("this patcher kit is missing its packaged tools/audio directory; rebuild the kit with audio tools");
    }

    private static Path checkedAudioTool(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        rejectSymlinkComponents(absolute);
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(absolute)) {
            throw new IOException("required packaged audio tool is missing or unreadable: " + absolute);
        }
        return absolute;
    }

    private static Path checkedExecutable(Path path, String label) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if ("FFmpeg 6.1.1".equals(label)) rejectSymlinkComponents(absolute);
        if (!isExecutable(absolute)) {
            throw new IOException(label + " is not a readable executable: " + absolute);
        }
        String argument = "Node.js 20+".equals(label) ? "--version" : "-version";
        String version = "Node.js 20+".equals(label) ? "20+" : "6.1.1";
        if (!versionMatches(absolute, argument, version)) {
            throw new IOException(label + " version check failed: " + absolute);
        }
        return absolute;
    }

    private static void rejectSymlinkComponents(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) throw new IOException("path is not absolute: " + path);
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            throw new IOException("symlink in audio path: " + current);
        }
        for (Path part : absolute) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("symlink in audio path: " + current);
            }
        }
    }

    private static Path uniqueAudioRebuildOutput(Path cacheDirectory) throws IOException {
        Path cache = cacheDirectory.toAbsolutePath().normalize();
        rejectSymlinkComponents(cache);
        if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("audio cache directory is missing: " + cache);
        }
        for (int attempt = 0; attempt < 100; attempt++) {
            Path candidate = cache.resolve("epk-rebuild-" + UUID.randomUUID());
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        throw new IOException("could not reserve a unique absent audio rebuild directory");
    }

    private static List<String> audioRebuildCommand(Path node, Path script, Path metadata, Path index,
            Path objects, Path ffmpeg, Path output, Path pako) {
        return List.of(node.toAbsolutePath().normalize().toString(), script.toAbsolutePath().normalize().toString(),
                "--metadata", metadata.toAbsolutePath().normalize().toString(),
                "--index", index.toAbsolutePath().normalize().toString(),
                "--objects", objects.toAbsolutePath().normalize().toString(),
                "--ffmpeg", ffmpeg.toAbsolutePath().normalize().toString(),
                "--pako", pako.toAbsolutePath().normalize().toString(),
                "--output", output.toAbsolutePath().normalize().toString(), "--group", "both");
    }

    private static int runAudioRebuild(List<String> command, Consumer<String> progress,
            Consumer<Process> onStart, AtomicBoolean cleanupConfirmed) throws IOException {
        Process process = null;
        Map<Long, ProcessHandle> capturedProcesses = new LinkedHashMap<>();
        boolean rootReaped = false;
        boolean restoreInterrupt = false;
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "eagler-audio-rebuild-output");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> outputReader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            process = builder.start();
            cleanupConfirmed.set(false);
            rememberAudioProcessTree(process, capturedProcesses);
            onStart.accept(process);
            Process started = process;
            outputReader = readerExecutor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        progress.accept(line);
                    }
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            while (!process.waitFor(40L, TimeUnit.MILLISECONDS)) {
                rememberAudioProcessTree(process, capturedProcesses);
            }
            rootReaped = true;
            rememberAudioProcessTree(process, capturedProcesses);
            int exit = process.exitValue();
            outputReader.get();
            return exit;
        } catch (InterruptedException error) {
            restoreInterrupt = true;
            throw new IOException("audio rebuild was interrupted", error);
        } catch (ExecutionException error) {
            throw new IOException("could not read audio rebuild output", error.getCause());
        } finally {
            readerExecutor.shutdownNow();
            if (process == null) {
                cleanupConfirmed.set(true);
            } else {
                rememberAudioProcessTree(process, capturedProcesses);
                if (process.isAlive() || hasLiveAudioProcess(capturedProcesses)) {
                    try {
                        terminateProcessTreeNow(new ArrayList<>(capturedProcesses.values()));
                    } catch (RuntimeException ignored) {
                        // Confirmation below checks every captured descendant, not only the root.
                    }
                }
                if (!rootReaped) {
                    try {
                        rootReaped = process.waitFor(2L, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        restoreInterrupt = true;
                    }
                }
                rememberAudioProcessTree(process, capturedProcesses);
                cleanupConfirmed.set(audioProcessTreeCleanupConfirmed(process,
                        capturedProcesses.values(), rootReaped));
            }
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private static void rememberAudioProcessTree(Process process, Map<Long, ProcessHandle> capturedProcesses) {
        for (ProcessHandle handle : processTreeHandles(process)) {
            capturedProcesses.putIfAbsent(handle.pid(), handle);
        }
    }

    private static boolean hasLiveAudioProcess(Map<Long, ProcessHandle> capturedProcesses) {
        return capturedProcesses.values().stream().anyMatch(ProcessHandle::isAlive);
    }

    private static boolean audioProcessTreeCleanupConfirmed(Process process, Iterable<ProcessHandle> handles,
            boolean rootReaped) {
        if (process == null || !rootReaped || process.isAlive()) return false;
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) return false;
        }
        return true;
    }

    private static Map<String, VerifiedAudioArchive> verifyAudioRebuild(Path output, Path objects,
            long startedMillis) throws IOException {
        Path rebuild = output.toAbsolutePath().normalize();
        rejectSymlinkComponents(rebuild);
        if (!Files.isDirectory(rebuild, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("audio rebuild did not create its unique output directory");
        }
        Path receiptPath = rebuild.resolve("final.json");
        rejectSymlinkComponents(receiptPath);
        if (!Files.isRegularFile(receiptPath, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(receiptPath)
                || Files.size(receiptPath) > 1_000_000L) {
            throw new IOException("fresh audio rebuild final.json receipt is missing or invalid");
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readAllBytes(receiptPath), "audio rebuild receipt");
        } catch (Json.JsonError error) {
            throw new IOException("audio rebuild final.json is invalid", error);
        }
        Map<String, AudioReceiptArchive> receiptArchives = validateAudioReceipt(parsed, rebuild, objects,
                startedMillis);
        Map<String, VerifiedAudioArchive> verified = new LinkedHashMap<>();
        verifyAudioArchive(receiptArchives.get("sounds"), verified);
        verifyAudioArchive(receiptArchives.get("music"), verified);
        return Map.copyOf(verified);
    }

    private static Map<String, AudioReceiptArchive> validateAudioReceipt(Object parsed, Path rebuildPath,
            Path objectsPath, long startedMillis) throws IOException {
        Path rebuild = rebuildPath.toAbsolutePath().normalize();
        Map<?, ?> receipt = requireJsonMap(parsed, "audio rebuild receipt");
        if (!"exact".equals(jsonString(receipt, "status"))
                || !AUDIO_INDEX_SHA1.equals(jsonString(receipt, "index_sha1"))
                || !AUDIO_METADATA_SHA256.equals(jsonString(receipt, "metadata_sha256"))
                || !rebuild.toString().equals(jsonString(receipt, "output"))
                || !objectsPath.toAbsolutePath().normalize().toString().equals(jsonString(receipt, "objects_root"))
                || !jsonString(receipt, "ffmpeg").startsWith("ffmpeg version 6.1.1")
                || jsonLong(receipt, "started_unix") < startedMillis / 1000L) {
            throw new IOException("audio rebuild receipt is stale or does not bind to the verified official inputs");
        }
        Map<?, ?> groups = requireJsonMap(receipt.get("groups"), "audio rebuild groups");
        if (groups.size() != 2) throw new IOException("audio rebuild receipt must contain both audio groups only");
        Map<String, AudioReceiptArchive> archives = new LinkedHashMap<>();
        archives.put("sounds", validateAudioReceiptArchive(rebuild, groups, "sounds", SOUNDS_ARCHIVE_SHA256, 4779));
        archives.put("music", validateAudioReceiptArchive(rebuild, groups, "music", MUSIC_ARCHIVE_SHA256, 92));
        return Map.copyOf(archives);
    }

    private static AudioReceiptArchive validateAudioReceiptArchive(Path output, Map<?, ?> groups, String name,
            String expectedSha256, long expectedMembers) throws IOException {
        Map<?, ?> group = requireJsonMap(groups.get(name), "audio rebuild " + name + " result");
        if (!"exact".equals(jsonString(group, "status"))
                || !expectedSha256.equals(jsonString(group, "expected_archive_sha256"))
                || !expectedSha256.equals(jsonString(group, "candidate_archive_sha256"))
                || !output.resolve(name + ".epk").toString().equals(jsonString(group, "archive_path"))
                || jsonLong(group, "expected_members") != expectedMembers
                || jsonLong(group, "processed_members") != expectedMembers
                || jsonLong(group, "remaining_members") != 0L) {
            throw new IOException("audio rebuild receipt is not exact for " + name);
        }
        long receiptSize = jsonLong(group, "archive_size");
        if (receiptSize <= 0L || receiptSize > 1_000_000_000L) {
            throw new IOException("audio rebuild receipt has an invalid " + name + " archive size");
        }
        return new AudioReceiptArchive(name, expectedSha256, receiptSize,
                output.resolve(name + ".epk").toString());
    }

    private static void verifyAudioArchive(AudioReceiptArchive receipt,
            Map<String, VerifiedAudioArchive> verified) throws IOException {
        String name = receipt.name();
        Path archive = Path.of(receipt.archivePath());
        rejectSymlinkComponents(archive);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(archive)
                || Files.size(archive) != receipt.size()) {
            throw new IOException("rebuilt " + name + ".epk is missing or its size differs from final.json");
        }
        String actualSha256 = sha256(archive);
        if (!receipt.sha256().equals(actualSha256)) {
            throw new IOException("rebuilt " + name + ".epk SHA-256 differs from the pinned archive hash");
        }
        verified.put(name, new VerifiedAudioArchive(archive, actualSha256, receipt.size()));
    }

    private static Map<?, ?> requireJsonMap(Object value, String label) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException(label + " is missing or invalid");
        return map;
    }

    private static String jsonString(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String string)) throw new IOException("audio rebuild receipt has invalid " + key);
        return string;
    }

    private static long jsonLong(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof Long number)) throw new IOException("audio rebuild receipt has invalid " + key);
        return number;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeAudioPin(Path path, String sha256) throws IOException {
        rejectSymlinkComponents(path);
        Files.writeString(path, sha256 + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
    }

    private static void publishAudioFilesNoOverwrite(List<AudioPublishFile> files) throws IOException {
        publishAudioFilesNoOverwrite(files, true);
    }

    private static void publishAudioFilesNoOverwrite(List<AudioPublishFile> files, boolean allowHardLinks)
            throws IOException {
        publishAudioFilesNoOverwrite(files, allowHardLinks, target -> { });
    }

    private static void publishAudioFilesNoOverwrite(List<AudioPublishFile> files, boolean allowHardLinks,
            AudioPublishMoveHook beforeFallbackMove) throws IOException {
        List<AudioPublishFile> normalized = new ArrayList<>();
        for (AudioPublishFile file : files) {
            Path source = file.source().toAbsolutePath().normalize();
            Path target = file.target().toAbsolutePath().normalize();
            rejectSymlinkComponents(source);
            rejectSymlinkComponents(target.getParent());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(source)) {
                throw new IOException("verified audio publish source is missing or invalid: " + source);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("audio publish target already exists and was preserved: " + target);
            }
            normalized.add(new AudioPublishFile(source, target));
        }
        List<PublishedAudioFile> published = new ArrayList<>();
        List<Path> temporaryFiles = new ArrayList<>();
        boolean hardLinksSupported = allowHardLinks;
        try {
            for (AudioPublishFile file : normalized) {
                if (hardLinksSupported) {
                    try {
                        Files.createLink(file.target(), file.source());
                        published.add(new PublishedAudioFile(file, true, null));
                        continue;
                    } catch (UnsupportedOperationException | IOException linkError) {
                        if (Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("audio publish target became occupied and was preserved: "
                                    + file.target(), linkError);
                        }
                        hardLinksSupported = false;
                    }
                }
                Path temporary = Files.createTempFile(file.target().getParent(),
                        ".eagler-audio-publish-", ".tmp");
                temporaryFiles.add(temporary);
                Files.copy(file.source(), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (Files.size(temporary) != Files.size(file.source())
                        || !sha256(temporary).equals(sha256(file.source()))) {
                    throw new IOException("audio publish fallback copy did not match its verified source");
                }
                Object fileKey = Files.readAttributes(temporary, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey();
                beforeFallbackMove.beforeMove(file.target());
                Files.move(temporary, file.target());
                temporaryFiles.remove(temporary);
                published.add(new PublishedAudioFile(file, false, fileKey));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            IOException rollbackFailure = null;
            for (int index = published.size() - 1; index >= 0; index--) {
                PublishedAudioFile publishedFile = published.get(index);
                AudioPublishFile file = publishedFile.file();
                try {
                    if (Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(file.target())
                            && (publishedFile.linked()
                                    ? Files.isSameFile(file.source(), file.target())
                                    : publishedFile.fileKey() != null
                                            && publishedFile.fileKey().equals(Files.readAttributes(file.target(),
                                                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey()))) {
                        Files.delete(file.target());
                    }
                } catch (IOException cleanupError) {
                    if (rollbackFailure == null) rollbackFailure = cleanupError;
                    else rollbackFailure.addSuppressed(cleanupError);
                }
            }
            IOException failure = new IOException(
                    "audio files could not all be published without overwriting; rebuild outputs remain in the cache",
                    error);
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            throw failure;
        } finally {
            for (Path temporary : temporaryFiles) {
                try {
                    if (!Files.isSymbolicLink(temporary)) Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Staged copies use random names and are never treated as published inputs.
                }
            }
        }
    }

    private boolean isWebBuildSelected() {
        return BUILD_STANDALONE.equals(mode.getSelectedItem()) || BUILD_IWA.equals(mode.getSelectedItem());
    }

    private boolean audioOutputTargetsAbsent() {
        Path appDirectory = codeSource().getParent();
        if (appDirectory == null) return false;
        Path inputs = appDirectory.resolve("inputs").toAbsolutePath().normalize();
        try {
            rejectSymlinkComponents(inputs);
            for (String name : List.of("sounds.epk", "sounds.epk.sha256", "music.epk", "music.epk.sha256")) {
                if (Files.exists(inputs.resolve(name), LinkOption.NOFOLLOW_LINKS)) return false;
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean shouldAutoPrepareAudio(boolean webBuild, boolean explicitSoundsPath,
            boolean audioTargetsAbsent) {
        return webBuild && !explicitSoundsPath && audioTargetsAbsent;
    }

    private static boolean shouldStartAudioPreparation(boolean audioNeeded, boolean prerequisitesReady) {
        return audioNeeded && prerequisitesReady;
    }

    private static boolean isExpectedSha256(String expectedSha256, String actualSha256) {
        return expectedSha256 != null && expectedSha256.equals(actualSha256);
    }

    private static boolean shouldAdoptPreparedAudio(boolean customAudioEdited) {
        return !customAudioEdited;
    }

    private static boolean shouldResumeRunAfterAudio(boolean requested, boolean succeeded, boolean webBuild) {
        return requested && succeeded && webBuild;
    }

    private boolean preflightBeforeAudio() {
        try {
            boolean standalone = BUILD_STANDALONE.equals(mode.getSelectedItem());
            boolean iwa = BUILD_IWA.equals(mode.getSelectedItem());
            requirePinnedFile("jar", "official 26.2 client JAR", Main.expectedClientJarSha256());
            requireFile("vineflower", "Vineflower JAR");
            requireFile("java17", "Java 17 executable");
            requirePinnedFile("patchBundle", "source patch bundle ZIP", PatchEngine.acceptedBundleSha256());
            requireProjectOutput();
            if (hasValue("skeleton")) {
                fields.get("skeletonSha").setText(ProjectSkeleton.acceptedArchiveSha256());
            } else {
                fields.get("skeletonSha").setText("");
            }
            fields.get("resourceSha").setText(hasValue("resourceOverlay")
                    ? contentProfileSpec((String)contentProfile.getSelectedItem()).resourceOverlaySha256() : "");
            optionalPair("skeleton", "skeletonSha", "project skeleton");
            optionalPair("resourceOverlay", "resourceSha", "resource overlay");
            if (hasValue("skeleton")) {
                requirePinnedFile("skeleton", "project skeleton ZIP", ProjectSkeleton.acceptedArchiveSha256());
            }
            if (hasValue("resourceOverlay")) {
                requirePinnedFile("resourceOverlay", "resource overlay ZIP",
                        contentProfileSpec((String)contentProfile.getSelectedItem()).resourceOverlaySha256());
            }
            if (hasValue("resourceOverlay") != hasValue("externalRoot")) {
                throw new IllegalArgumentException("External resource root must be supplied exactly when the resource overlay is supplied.");
            }
            if (hasValue("externalRoot")) requireDirectory("externalRoot", "external resource root");
            requireFile("java25", "Java 25 executable");
            requireFile("node", "Node.js executable");
            requireFile("npm", "npm CLI or launcher");
            if (!toolVersionMatches("java17", "-version", "17")
                    || !toolVersionMatches("java25", "-version", "25")
                    || !toolVersionMatches("node", "--version", "20+")
                    || !validNpmCli("npm")) {
                throw new IllegalArgumentException("Web build tools are missing or have the wrong version.");
            }
            if (!hasValue("skeleton") || !hasValue("resourceOverlay")
                    || !directoryAvailable("externalRoot")) {
                throw new IllegalArgumentException("Web build requires both the project skeleton ZIP, resource overlay ZIP, and external resources.");
            }
            if (standalone) {
                requireOutput("standaloneOutput", "standalone HTML output", false);
                if (includeWispcraft.isSelected()) requireWispcraftScript();
                if (!embedMusicInHtml.isSelected()) requireMusicPackOutputAvailable();
            }
            if (iwa) {
                requireOutput("iwaOutput", "signed IWA output", false);
                if (!path("iwaOutput").getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".swbn")) {
                    throw new IllegalArgumentException("Signed IWA output filename must end in .swbn.");
                }
                if (iwaOutputSidecarExists()) {
                    throw new IllegalArgumentException("An IWA sidecar already exists beside that output; choose another .swbn filename.");
                }
                if (hasValue("standaloneOutput")) {
                    requireOutput("standaloneOutput", "browser-test HTML output", false);
                    if (!path("standaloneOutput").getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html")) {
                        throw new IllegalArgumentException("Browser-test HTML filename must end in .html.");
                    }
                    requireMusicPackOutputAvailable();
                }
                if (!iwaKeyUsable()) {
                    throw new IllegalArgumentException("Choose a new key path or an existing readable signing key.");
                }
            }
            return true;
        } catch (IllegalArgumentException ex) {
            runAfterAudio = false;
            showError(ex.getMessage());
            return false;
        }
    }

    private void startRun() {
        if ((worker != null && !worker.isDone()) || (setupWorker != null && !setupWorker.isDone())
                || (resourceWorker != null && !resourceWorker.isDone())
                || audioTaskActive.get() || (audioWorker != null && !audioWorker.isDone())
                || toolDiscoveryPending || runLocked) {
            return;
        }
        refreshBundledInputs();
        if (!readableFile("patchBundle") || !readableFile("vineflower")
                || isWebBuildSelected() && (!readableFile("skeleton") || !readableFile("resourceOverlay"))) {
            showError("This copy is missing bundled build files. Open the complete Setup JAR and choose a new setup folder; you do not need to select or extract ZIP files.");
            return;
        }
        if (hasValue("resourceOverlay") && !hasValue("externalRoot")) {
            runAfterResources = true;
            getOfficialResources();
            return;
        }
        boolean audioNeeded = shouldAutoPrepareAudio(isWebBuildSelected(), hasValue("sounds"),
                audioOutputTargetsAbsent());
        if (audioNeeded) {
            if (!preflightBeforeAudio()) return;
            runAfterAudio = true;
            getGameAudio();
            return;
        }
        lastSuccessfulFolder = null;
        openFolder.setEnabled(false);
        try {
            List<String> command = commandFromForm();
            log.setText("");
            progress.setValue(0);
            progress.setIndeterminate(true);
            progress.setString("Starting…");
            progressLabel.setText("Starting…");
            activeProgressStage = "Starting";
            progressElapsedAtUpdate = 0L;
            progressUpdateNanos = System.nanoTime();
            progressHeartbeat.start();
            worker = new RunWorker(command);
            worker.execute();
            cancelPending = false;
            updateActionState();
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
        }
    }

    private List<String> commandFromForm() {
        boolean standalone = BUILD_STANDALONE.equals(mode.getSelectedItem());
        boolean iwa = BUILD_IWA.equals(mode.getSelectedItem());
        boolean webBuild = standalone || iwa;
        PackagedMusic music = null;
        requireFile("jar", "official 26.2 client JAR");
        requireFile("vineflower", "Vineflower JAR");
        requireFile("java17", "Java 17 executable");
        requireFile("patchBundle", "source patch bundle ZIP");
        requireHash("patchSha", "patch bundle SHA-256");
        requireProjectOutput();
        if (hasValue("skeleton")) {
            fields.get("skeletonSha").setText(ProjectSkeleton.acceptedArchiveSha256());
        } else {
            fields.get("skeletonSha").setText("");
        }
        fields.get("resourceSha").setText(hasValue("resourceOverlay")
                ? contentProfileSpec((String)contentProfile.getSelectedItem()).resourceOverlaySha256() : "");
        optionalPair("skeleton", "skeletonSha", "project skeleton");
        optionalPair("resourceOverlay", "resourceSha", "resource overlay");
        if (hasValue("resourceOverlay") != hasValue("externalRoot")) {
            throw new IllegalArgumentException("External resource root must be supplied exactly when the resource overlay is supplied.");
        }
        if (hasValue("externalRoot")) {
            requireDirectory("externalRoot", "external resource root");
        }
        if (webBuild) {
            requireFile("java25", "Java 25 executable");
            requireFile("node", "Node.js executable");
            requireFile("npm", "npm CLI or launcher");
            requireFile("sounds", "authenticated sounds.epk");
            requireHash("soundsSha", "sounds EPK SHA-256");
            music = packagedMusic();
            if (!hasValue("skeleton") || !hasValue("resourceOverlay")) {
                throw new IllegalArgumentException("Web build requires both the project skeleton ZIP and resource overlay ZIP.");
            }
            if (standalone) {
                requireOutput("standaloneOutput", "standalone HTML output", false);
            }
            if (iwa) {
                requireOutput("iwaOutput", "signed IWA output", false);
                if (!path("iwaOutput").getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".swbn")) {
                    throw new IllegalArgumentException("Signed IWA output filename must end in .swbn.");
                }
                if (iwaOutputSidecarExists()) {
                    throw new IllegalArgumentException("An IWA sidecar already exists beside that output; choose another filename.");
                }
                if (hasValue("standaloneOutput")) {
                    requireOutput("standaloneOutput", "browser-test HTML output", false);
                    if (!path("standaloneOutput").getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".html")) {
                        throw new IllegalArgumentException("Browser-test HTML filename must end in .html.");
                    }
                    requireMusicPackOutputAvailable();
                }
                if (!iwaKeyUsable()) {
                    throw new IllegalArgumentException("Choose a new signing-key path or an existing readable signing key.");
                }
            }
            if (standalone && includeWispcraft.isSelected()) {
                requireWispcraftScript();
            }
            if (standalone && !embedMusicInHtml.isSelected()) {
                requireMusicPackOutputAvailable();
            }
        }
        Map<String, String> values = fieldValues();
        if (webBuild) {
            values.put("musicEpk", music.path().toString());
            values.put("musicSha", music.sha256());
        }
        return buildCommandArguments(standalone, iwa, reusableProjectSelected(), includeWispcraft.isSelected(),
                embedMusicInHtml.isSelected(), values);
    }

    private Map<String, String> fieldValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getText().trim());
        }
        if (hasValue("standaloneOutput")) {
            values.put("musicPackOutput", musicPackOutputPath().toString());
        }
        return values;
    }

    private static List<String> buildCommandArguments(boolean standalone, boolean iwa, boolean reuseProject,
            boolean useWispcraft, boolean embedMusic, Map<String, String> values) {
        List<String> command = new ArrayList<>();
        command.add(standalone ? "build-standalone" : iwa ? "build-iwa" : "create-dev");
        if (standalone && reuseProject) command.add("--reuse-project");
        addPathArgument(command, "--jar", values.get("jar"));
        addPathArgument(command, "--output", values.get("output"));
        addPathArgument(command, "--vineflower", values.get("vineflower"));
        addPathArgument(command, "--java17", values.get("java17"));
        addPathArgument(command, "--patch-bundle", values.get("patchBundle"));
        addLiteralArgument(command, "--expected-bundle-sha256", values.get("patchSha"));
        addOptionalPathArgument(command, "--project-skeleton", values.get("skeleton"));
        addOptionalLiteralArgument(command, "--expected-skeleton-sha256", values.get("skeletonSha"));
        addOptionalPathArgument(command, "--resource-overlay", values.get("resourceOverlay"));
        addOptionalLiteralArgument(command, "--expected-resource-overlay-sha256", values.get("resourceSha"));
        addOptionalPathArgument(command, "--external-resource-root", values.get("externalRoot"));
        if (standalone || iwa) {
            addPathArgument(command, "--java25", values.get("java25"));
            addPathArgument(command, "--node", values.get("node"));
            addPathArgument(command, "--npm", values.get("npm"));
            addPathArgument(command, "--sounds-epk", values.get("sounds"));
            addLiteralArgument(command, "--expected-sounds-epk-sha256", values.get("soundsSha"));
            addPathArgument(command, "--music-epk", values.get("musicEpk"));
            addLiteralArgument(command, "--expected-music-epk-sha256", values.get("musicSha"));
            if (standalone) {
                addPathArgument(command, "--standalone-output", values.get("standaloneOutput"));
                if (embedMusic) {
                    command.add("--with-music");
                } else {
                    addPathArgument(command, "--music-pack-output", values.get("musicPackOutput"));
                }
                if (useWispcraft) {
                    addPathArgument(command, "--wispcraft-script", values.get("wispcraftScript"));
                }
            } else {
                addPathArgument(command, "--iwa-output", values.get("iwaOutput"));
                addOptionalPathArgument(command, "--standalone-output", values.get("standaloneOutput"));
                addOptionalPathArgument(command, "--iwa-key", values.get("iwaKey"));
            }
        }
        return command;
    }

    private static void addPathArgument(List<String> command, String option, String value) {
        command.add(option);
        command.add(Path.of(value).toAbsolutePath().normalize().toString());
    }

    private static void addOptionalPathArgument(List<String> command, String option, String value) {
        if (value != null && !value.isBlank()) {
            addPathArgument(command, option, value.trim());
        }
    }

    private static void addLiteralArgument(List<String> command, String option, String value) {
        command.add(option);
        command.add(value.trim());
    }

    private static void addOptionalLiteralArgument(List<String> command, String option, String value) {
        if (value != null && !value.isBlank()) {
            addLiteralArgument(command, option, value);
        }
    }

    private static PackagedMusic packagedMusic() {
        Path appDirectory = codeSource().getParent();
        if (appDirectory == null) {
            throw new IllegalArgumentException("The patcher application directory could not be located.");
        }
        Path input = appDirectory.resolve("inputs/music.epk").toAbsolutePath().normalize();
        Path pin = input.resolveSibling("music.epk.sha256");
        String missingMessage = "Local standalone music is unavailable: packaged inputs/music.epk and its valid "
                + "music.epk.sha256 pin are required. The official client JAR contains no music; rebuild the local patcher kit.";
        if (!Files.isRegularFile(input) || !Files.isReadable(input)
                || !Files.isRegularFile(pin) || !Files.isReadable(pin)) {
            throw new IllegalArgumentException(missingMessage);
        }
        try {
            if (Files.size(pin) > 128L) throw new IllegalArgumentException(missingMessage);
            String sha256 = Files.readString(pin, StandardCharsets.UTF_8).trim();
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(missingMessage);
            return new PackagedMusic(input, sha256);
        } catch (IOException ex) {
            throw new IllegalArgumentException(missingMessage, ex);
        }
    }

    private static boolean packagedMusicAvailable() {
        try {
            packagedMusic();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private record PackagedMusic(Path path, String sha256) {
    }

    private record AudioPreparationResult(Path sounds, String soundsSha256, Path music, String musicSha256,
            Path rebuildDirectory, int verifiedObjects) {
    }

    private record VerifiedAudioArchive(Path path, String sha256, long size) {
    }

    private record AudioReceiptArchive(String name, String sha256, long size, String archivePath) {
    }

    private record AudioPublishFile(Path source, Path target) {
    }

    private record PublishedAudioFile(AudioPublishFile file, boolean linked, Object fileKey) {
    }

    @FunctionalInterface
    private interface AudioPublishMoveHook {
        void beforeMove(Path target) throws IOException;
    }

    private void requireFile(String key, String label) {
        if (!hasValue(key)) {
            throw new IllegalArgumentException(label + " is required.");
        }
        Path path = path(key);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(label + " is not a readable regular file: " + path);
        }
    }

    private void requirePinnedFile(String key, String label, String expectedSha256) {
        requireFile(key, label);
        try {
            String actualSha256 = sha256(path(key));
            if (!isExpectedSha256(expectedSha256, actualSha256)) {
                throw new IllegalArgumentException(label + " SHA-256 mismatch: expected "
                        + expectedSha256 + ", got " + actualSha256 + ".");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(label + " could not be hashed: " + path(key), ex);
        }
    }

    private void requireWispcraftScript() {
        requireFile("wispcraftScript", "Wispcraft compiled dist/index.js");
        Path script = path("wispcraftScript");
        Path parent = script.getParent();
        if (!"index.js".equals(script.getFileName().toString()) || parent == null
                || !"dist".equals(parent.getFileName().toString())) {
            throw new IllegalArgumentException("Choose Wispcraft's compiled dist/index.js file.");
        }
    }

    private boolean validWispcraftScript() {
        if (!hasValue("wispcraftScript")) return false;
        try {
            Path script = path("wispcraftScript");
            Path parent = script.getParent();
            return Files.isRegularFile(script) && Files.isReadable(script)
                    && "index.js".equals(script.getFileName().toString()) && parent != null
                    && "dist".equals(parent.getFileName().toString());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private Path musicPackOutputPath() {
        Path html = path("standaloneOutput");
        String filename = html.getFileName().toString();
        int extension = filename.lastIndexOf('.');
        String stem = extension > 0 ? filename.substring(0, extension) : filename;
        return html.resolveSibling(stem + "-music-resource-pack.zip");
    }

    private boolean musicPackOutputExists() {
        if (!hasValue("standaloneOutput")) return false;
        try {
            return Files.exists(musicPackOutputPath());
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private boolean iwaOutputSidecarExists() {
        if (!hasValue("iwaOutput")) return false;
        try {
            Path output = path("iwaOutput");
            Path parent = output.getParent();
            if (parent == null || output.getFileName() == null) return true;
            String name = output.getFileName().toString();
            return Files.exists(parent.resolve(name + ".receipt.json"),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(parent.resolve(name + ".info.txt"),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(parent.resolve(name + ".sha256"),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private boolean iwaKeyUsable() {
        if (!hasValue("iwaKey")) return true;
        try {
            Path key = path("iwaKey");
            return !Files.exists(key, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || (!Files.isSymbolicLink(key) && Files.isRegularFile(key,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isReadable(key));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void requireMusicPackOutputAvailable() {
        Path output = musicPackOutputPath();
        if (Files.exists(output)) {
            throw new IllegalArgumentException(
                    "Music resource-pack output already exists; choose another HTML filename: " + output);
        }
    }

    private void requireDirectory(String key, String label) {
        if (!hasValue(key) || !Files.isDirectory(path(key))) {
            throw new IllegalArgumentException(label + " is not a directory.");
        }
    }

    private void requireOutput(String key, String label, boolean directory) {
        if (!hasValue(key)) {
            throw new IllegalArgumentException(label + " is required.");
        }
        Path output = path(key);
        if (Files.exists(output)) {
            if (directory && (!Files.isDirectory(output) || hasChildren(output))) {
                throw new IllegalArgumentException(label + " must be absent or an empty directory: " + output);
            }
            if (!directory) {
                throw new IllegalArgumentException(label + " must be absent: " + output);
            }
        }
    }

    private void requireProjectOutput() {
        if (!reusableProjectSelected()) {
            requireOutput("output", "development workspace output", true);
            return;
        }
        if (!hasValue("output")) {
            throw new IllegalArgumentException("An existing patched project folder is required for reuse.");
        }
        Path output = path("output");
        if (!Files.isDirectory(output) || !Files.isReadable(output)) {
            throw new IllegalArgumentException("Choose a readable existing project folder for reuse: " + output);
        }
    }

    private void optionalPair(String pathKey, String hashKey, String label) {
        if (hasValue(pathKey) != hasValue(hashKey)) {
            throw new IllegalArgumentException(label + " and its SHA-256 must be supplied together.");
        }
        if (hasValue(pathKey)) {
            requireFile(pathKey, label);
            requireHash(hashKey, label + " SHA-256");
        }
    }

    private void requireHash(String key, String label) {
        if (!fields.get(key).getText().trim().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be 64 lowercase hexadecimal characters.");
        }
    }

    private boolean hasValue(String key) {
        return !fields.get(key).getText().trim().isEmpty();
    }

    private Path path(String key) {
        return Path.of(fields.get(key).getText().trim()).toAbsolutePath().normalize();
    }

    private static boolean hasChildren(Path path) {
        try (var stream = Files.list(path)) {
            return stream.findFirst().isPresent();
        } catch (IOException ex) {
            return true;
        }
    }

    private void cancelRun() {
        if (audioTaskActive.get() && audioWorker != null && !audioCancelPending) {
            runAfterAudio = false;
            audioCancelPending = true;
            progressLabel.setText("Stopping audio and preserving verified cache…");
            audioWorker.cancel(true);
            updateActionState();
            return;
        }
        if (!(worker instanceof RunWorker current) || current.isDone() || cancelPending) {
            return;
        }
        cancelPending = true;
        progressLabel.setText("Stopping the patch process…");
        current.requestCancel();
        updateActionState();
    }

    private Future<?> terminateProcessTree(Process process) {
        List<ProcessHandle> handles = processTreeHandles(process);
        return cancellationExecutor.submit(() -> terminateProcessTreeNow(handles));
    }

    private static void terminateProcessTreeNow(Process process) {
        terminateProcessTreeNow(processTreeHandles(process));
    }

    private static List<ProcessHandle> processTreeHandles(Process process) {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> handles = new ArrayList<>(root.descendants().toList());
        handles.sort((left, right) -> Long.compare(right.pid(), left.pid()));
        handles.add(root);
        return handles;
    }

    private static void terminateProcessTreeNow(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            handle.destroy();
        }
        waitForExit(handles, 2_000L);
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        waitForExit(handles, 2_000L);
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                throw new IllegalStateException("child process tree did not exit after forced termination");
            }
        }
    }

    private static void waitForExit(List<ProcessHandle> handles, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            boolean alive = false;
            for (ProcessHandle handle : handles) {
                if (handle.isAlive()) {
                    alive = true;
                    break;
                }
            }
            if (!alive) {
                return;
            }
            try {
                Thread.sleep(40L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handleOutput(String line) {
        handleOutput(List.of(line));
    }

    private void handleOutput(List<String> lines) {
        StringBuilder batch = new StringBuilder();
        ProgressUpdate latestProgress = null;
        String latestStatus = null;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            ProgressUpdate progressUpdate = parseProgress(line.trim());
            if (progressUpdate != null) latestProgress = progressUpdate;
            if (ERROR_PREFIX.matcher(line.trim()).matches()) {
                latestStatus = line.trim();
            }
            batch.append(line).append('\n');
        }
        if (batch.length() == 0) return;

        if (latestProgress != null) applyProgressUpdate(latestProgress);
        if (latestStatus != null) progressLabel.setText(latestStatus);

        int logLength = log.getDocument().getLength();
        int keepFrom = Math.max(0, batch.length() - LOG_LIMIT);
        int overflow = Math.max(0, logLength + batch.length() - keepFrom - LOG_LIMIT);
        if (overflow > 0) log.replaceRange("", 0, overflow);
        log.append(batch.substring(keepFrom));
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static ProgressUpdate parseProgress(String line) {
        Matcher match = PROGRESS.matcher(line);
        if (!match.matches()) return null;
        return new ProgressUpdate(match.group(1), Long.parseLong(match.group(2)));
    }

    private void applyProgressUpdate(ProgressUpdate update) {
        if ("complete".equals(update.stage())) {
            progressHeartbeat.stop();
            activeProgressStage = null;
            progress.setIndeterminate(false);
            progress.setValue(100);
            progress.setString("Complete");
            progressLabel.setText("Done.");
            return;
        }

        long now = System.nanoTime();
        long currentlyDisplayed = currentProgressElapsed(now);
        activeProgressStage = update.stage();
        progressElapsedAtUpdate = Math.max(currentlyDisplayed, update.elapsedSeconds());
        progressUpdateNanos = now;
        progress.setIndeterminate(true);
        progress.setString("Working…");
        refreshProgressHeartbeat();
        if (!progressHeartbeat.isRunning()) progressHeartbeat.start();
    }

    private long currentProgressElapsed(long now) {
        if (activeProgressStage == null) return progressElapsedAtUpdate;
        long sinceUpdate = Math.max(0L, (now - progressUpdateNanos) / 1_000_000_000L);
        return progressElapsedAtUpdate + sinceUpdate;
    }

    private void refreshProgressHeartbeat() {
        if (activeProgressStage == null) return;
        progressLabel.setText(progressStatus(activeProgressStage, currentProgressElapsed(System.nanoTime())));
    }

    private static String progressStatus(String stage, long elapsedSeconds) {
        return stage + "  |  " + elapsedSeconds + "s elapsed";
    }

    private void stopProgressHeartbeat() {
        progressHeartbeat.stop();
        activeProgressStage = null;
        progress.setIndeterminate(false);
        progress.setString(null);
    }

    private void openLastSuccessfulFolder() {
        Path target = lastSuccessfulFolder;
        if (target == null) return;
        openFolder.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                openFolderInDesktop(target);
                return null;
            }

            @Override
            protected void done() {
                openFolder.setEnabled(lastSuccessfulFolder != null);
                try {
                    get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showOpenFolderError(target, "Opening was interrupted.");
                } catch (ExecutionException ex) {
                    showOpenFolderError(target, rootMessage(ex));
                }
            }
        }.execute();
    }

    private static void openFolderInDesktop(Path target) throws IOException {
        if (!Files.isDirectory(target)) {
            throw new IOException("The output folder no longer exists.");
        }
        IOException desktopFailure = null;
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(target.toFile());
                    return;
                }
            } catch (IOException ex) {
                desktopFailure = ex;
            } catch (UnsupportedOperationException | SecurityException ex) {
                desktopFailure = new IOException(ex.getMessage(), ex);
            }
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (isWindows(os)) {
            command = List.of("explorer.exe", target.toString());
        } else if (os.contains("linux")) {
            command = List.of("xdg-open", target.toString());
        } else if (os.contains("mac")) {
            command = List.of("open", target.toString());
        } else {
            IOException ex = new IOException("This operating system has no supported folder opener.");
            if (desktopFailure != null) ex.addSuppressed(desktopFailure);
            throw ex;
        }
        try {
            new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ex) {
            if (desktopFailure != null) ex.addSuppressed(desktopFailure);
            throw ex;
        }
    }

    private void showOpenFolderError(Path target, String detail) {
        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(frame,
                "Could not open the output folder:\n" + target + "\n" + detail,
                "Open folder failed", JOptionPane.ERROR_MESSAGE);
    }

    private static Path successfulOutputFolder(List<String> command) {
        if (command.isEmpty()) return null;
        String option = switch (command.get(0)) {
            case "create-dev" -> "--output";
            case "build-standalone" -> "--standalone-output";
            case "build-iwa" -> "--iwa-output";
            default -> null;
        };
        if (option == null) return null;
        for (int index = 1; index + 1 < command.size(); index++) {
            if (!option.equals(command.get(index))) continue;
            try {
                Path destination = Path.of(command.get(index + 1)).toAbsolutePath().normalize();
                return "create-dev".equals(command.get(0)) ? destination : destination.getParent();
            } catch (InvalidPathException ex) {
                return null;
            }
        }
        return null;
    }

    private void showError(String message) {
        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(frame, message, "Cannot start", JOptionPane.ERROR_MESSAGE);
    }

    private final class RunWorker extends SwingWorker<RunResult, String> {
        private final List<String> patcherArgs;
        private final Path successfulFolder;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile Process process;
        private volatile boolean processCleanupConfirmed = true;
        private Future<?> terminationTask;

        RunWorker(List<String> patcherArgs) {
            this.patcherArgs = List.copyOf(patcherArgs);
            this.successfulFolder = successfulOutputFolder(this.patcherArgs);
        }

        @Override
        protected RunResult doInBackground() throws Exception {
            if (isCancelled() || cancelRequested.get()) {
                throw new CancellationException();
            }
            List<String> command = new ArrayList<>();
            command.add(javaExecutable().toString());
            Path codeSource = codeSource();
            command.add("-cp");
            command.add(codeSource.toString());
            command.add(Main.class.getName());
            command.addAll(patcherArgs);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            Process started = builder.start();
            process = started;
            processCleanupConfirmed = false;
            ExecutorService readers = Executors.newFixedThreadPool(2);
            try {
                if (cancelRequested.get()) queueTermination(started);
                readers.submit(() -> readOutput(started.getInputStream()));
                readers.submit(() -> readOutput(started.getErrorStream()));
                int exit = started.waitFor();
                if (cancelRequested.get()) awaitTermination();
                readers.shutdown();
                readers.awaitTermination(5, TimeUnit.SECONDS);
                processCleanupConfirmed = !started.isAlive();
                return new RunResult(exit, cancelRequested.get(), processCleanupConfirmed);
            } finally {
                try {
                    if (started.isAlive() || cancelRequested.get()) {
                        Future<?> cleanup = queueTermination(started);
                        cleanup.get();
                    }
                    processCleanupConfirmed = !started.isAlive();
                } catch (InterruptedException ex) {
                    processCleanupConfirmed = false;
                    Thread.currentThread().interrupt();
                    throw ex;
                } catch (ExecutionException ex) {
                    processCleanupConfirmed = false;
                    throw new IOException("child process tree termination could not be confirmed", ex);
                } finally {
                    process = null;
                    readers.shutdownNow();
                }
            }
        }

        void requestCancel() {
            cancelRequested.set(true);
            Process started = process;
            if (started != null) {
                queueTermination(started);
            }
        }

        private synchronized Future<?> queueTermination(Process started) {
            if (terminationTask == null) {
                processCleanupConfirmed = false;
                terminationTask = terminateProcessTree(started);
            }
            return terminationTask;
        }

        private void awaitTermination() throws InterruptedException, ExecutionException {
            Future<?> pending;
            synchronized (this) {
                pending = terminationTask;
            }
            if (pending != null) pending.get();
        }

        private boolean wasCancelledByUser() {
            return cancelRequested.get();
        }

        private boolean processTreeStopped() {
            return processCleanupConfirmed;
        }

        private void readOutput(InputStream stream) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    publish(line);
                }
            } catch (IOException ex) {
                publish("GUI: output reader stopped: " + ex.getMessage());
            }
        }

        @Override
        protected void process(List<String> lines) {
            handleOutput(lines);
        }

        @Override
        protected void done() {
            stopProgressHeartbeat();
            try {
                RunResult result = get();
                if (!result.processTreeStopped) {
                    runLocked = true;
                    progressLabel.setText("Process still running. Restart the patcher before trying again.");
                } else if (result.cancelled) {
                    progress.setValue(0);
                    progressLabel.setText("Stopped. Checking temporary files...");
                } else if (result.exitCode == 0) {
                    progress.setValue(100);
                    progressLabel.setText("Done.");
                    lastSuccessfulFolder = successfulFolder;
                    openFolder.setEnabled(lastSuccessfulFolder != null);
                } else {
                    progressLabel.setText("Build failed (code " + result.exitCode + "). See Log.");
                    Toolkit.getDefaultToolkit().beep();
                }
            } catch (CancellationException ex) {
                if (wasCancelledByUser()) {
                    progressLabel.setText("Stopped. Checking temporary files...");
                } else {
                    progressLabel.setText("Stopped.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                progressLabel.setText("Interrupted.");
            } catch (ExecutionException ex) {
                if (!processTreeStopped()) {
                    runLocked = true;
                    progressLabel.setText("Process still running. Restart the patcher before trying again.");
                } else if (wasCancelledByUser()) {
                    progressLabel.setText("Stopped. Checking temporary files...");
                } else {
                    progressLabel.setText("Failed: " + rootMessage(ex));
                    showError(rootMessage(ex));
                }
            }
            cancelPending = false;
            updateReadiness();
        }
    }

    private record ContentProfile(String name, String patchBundleName, String patchBundleSha256,
            String resourceOverlayName, String resourceOverlaySha256, String description) {
    }

    private record ProfileInputs(Path patchBundle, String patchSha256,
            Path resourceOverlay, String resourceOverlaySha256) {
    }

    private record RunResult(int exitCode, boolean cancelled, boolean processTreeStopped) {
    }

    private record ProgressUpdate(String stage, long elapsedSeconds) {
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
    }

    private static Path codeSource() {
        try {
            return Path.of(GuiMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot locate the patcher JAR", ex);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static void selfTest() {
        if (!Files.isRegularFile(javaExecutable())) {
            throw new IllegalStateException("Java launcher not found: " + javaExecutable());
        }
        if (CREATE_DEV.equals(BUILD_STANDALONE)
                || !NORMAL_CONTENT_PROFILE.name().equals((String)new JComboBox<>(SOURCE_CONTENT_PROFILE_NAMES).getItemAt(0))
                || !"Normal".equals(NORMAL_CONTENT_PROFILE.name())
                || SOURCE_CONTENT_PROFILE_NAMES.length != 1
                || STANDALONE_CONTENT_PROFILE_NAMES.length != 1
                || IWA_CONTENT_PROFILE_NAMES.length != 1
                || !Arrays.equals(SOURCE_CONTENT_PROFILE_NAMES, STANDALONE_CONTENT_PROFILE_NAMES)
                || !Arrays.equals(SOURCE_CONTENT_PROFILE_NAMES, IWA_CONTENT_PROFILE_NAMES)
                || !profileAvailableForMode(NORMAL_CONTENT_PROFILE.name(), CREATE_DEV)
                || !profileAvailableForMode(NORMAL_CONTENT_PROFILE.name(), BUILD_STANDALONE)
                || !profileAvailableForMode(NORMAL_CONTENT_PROFILE.name(), BUILD_IWA)) {
            throw new IllegalStateException("GUI mode constants invalid");
        }
        JScrollPane scrollProbe = verticalScrollPane(new JPanel());
        if (scrollProbe.getHorizontalScrollBarPolicy() != JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            throw new IllegalStateException("GUI content can show a horizontal scrollbar");
        }
        verifyActionState();
        verifyBlankOutputDefaults();
        verifyDirectoryChooserPathDerivation();
        verifyContentProfileSelection();
        verifyCommandArguments();
        verifyOutputFolderRouting();
        verifyProjectProbeContract();
        verifyProgressContract();
        verifyPackagedMusicContract();
        verifyAudioRunTriggerContract();
        verifyAudioPreparationContract();
        try {
            Process child = new ProcessBuilder(javaExecutable().toString(), "-cp", codeSource().toString(),
                    GuiMain.class.getName(), "--self-test-child").start();
            terminateProcessTreeNow(child);
            if (child.isAlive()) {
                throw new IllegalStateException("child process survived cancellation");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("process launch self-test failed", ex);
        }
        System.out.println("gui-self-test: PASS");
        System.out.println("modes: 3");
        System.out.println("primary-inputs: official client JAR, workspace folder");
        System.out.println("horizontal-scroll: disabled");
        System.out.println("actions: setup/run exclusion and cancel lock PASS");
        System.out.println("directory chooser: existing parent and new child path handling PASS");
        System.out.println("arguments: create-dev, standalone, explicit project reuse, and IWA wiring PASS");
        System.out.println("project reuse: automatic recognized project, stale probe guard, and CLI validation PASS");
        System.out.println("output defaults: project, HTML, SWBN, and key paths blank PASS");
        System.out.println("open folder: successful source, standalone, and IWA destinations PASS");
        System.out.println("progress: indeterminate heartbeat with measured elapsed time; no fabricated ETA PASS");
        System.out.println("music: packaged input and missing-input contract PASS");
        System.out.println("audio run trigger: Standalone/IWA missing-input chain and fail-closed custom path PASS");
        System.out.println("audio preparation: source-bound exact receipt, fresh unique output, and no-overwrite publish PASS");
        System.out.println("content-profile: Normal in Source project, Standalone, and IWA; PASS");
        System.out.println("runner: existing com.eaglercraft.patcher.Main subprocess");
        System.out.println("cancel: off-EDT process-tree termination");
    }

    private static void verifyProjectProbeContract() {
        if (!currentProjectProbe(4, 4, "project", "project")
                || currentProjectProbe(3, 4, "project", "project")
                || currentProjectProbe(4, 4, "project", "other")) {
            throw new IllegalStateException("stale project probe result was accepted");
        }
        if (!shouldReuseProject(BUILD_STANDALONE, false, "project", "project")
                || shouldReuseProject(BUILD_STANDALONE, false, null, "new-project")
                || shouldReuseProject(BUILD_STANDALONE, false, null, "incompatible")
                || shouldReuseProject(CREATE_DEV, false, "project", "project")
                || shouldReuseProject(BUILD_IWA, false, "project", "project")) {
            throw new IllegalStateException("automatic project reuse selection is invalid");
        }
    }

    private static void verifyActionState() {
        ActionState idle = actionState(false, false, false, false, true, true, false);
        ActionState settingUp = actionState(false, true, false, false, true, true, false);
        ActionState patching = actionState(true, false, false, false, true, true, false);
        ActionState cancelling = actionState(true, false, false, false, true, true, true);
        ActionState cleanupUnknown = actionState(false, false, false, true, true, true, false);
        ActionState outputInUse = actionState(false, false, false, false, false, true, false);
        if (!idle.canRun || !idle.canSetup || idle.canCancel
                || settingUp.canRun || settingUp.canSetup || settingUp.canCancel
                || patching.canRun || patching.canSetup || !patching.canCancel
                || cancelling.canRun || cancelling.canSetup || cancelling.canCancel
                || cleanupUnknown.canRun || cleanupUnknown.canSetup
                || outputInUse.canRun || !outputInUse.canSetup) {
            throw new IllegalStateException("GUI action exclusion state is invalid");
        }
    }

    private static void verifyBlankOutputDefaults() {
        Map<String, JTextField> defaults = new LinkedHashMap<>();
        for (String key : OUTPUT_PATH_KEYS) defaults.put(key, new JTextField("development-default"));
        clearOutputDefaults(defaults);
        for (String key : OUTPUT_PATH_KEYS) {
            if (!defaults.get(key).getText().isEmpty()) {
                throw new IllegalStateException("GUI output path was not blank by default: " + key);
            }
        }
    }

    private static void verifyOutputFolderRouting() {
        Path project = Path.of("selected/project").toAbsolutePath().normalize();
        Path outputParent = Path.of("selected").toAbsolutePath().normalize();
        if (!project.equals(successfulOutputFolder(List.of("create-dev", "--output", "selected/project")))
                || !outputParent.equals(successfulOutputFolder(
                        List.of("build-standalone", "--standalone-output", "selected/game.html")))
                || !outputParent.equals(successfulOutputFolder(
                        List.of("build-iwa", "--iwa-output", "selected/game.swbn")))
                || successfulOutputFolder(List.of("create-dev", "--output")) != null
                || successfulOutputFolder(List.of("other")) != null) {
            throw new IllegalStateException("Open folder destination does not match the successful build output");
        }
    }

    private static void verifyDirectoryChooserPathDerivation() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (!Files.isDirectory(current) || !directoryChooserStart(current).equals(current)) {
            throw new IllegalStateException("directory chooser did not retain the selected existing folder");
        }
        Path missingChild = current.resolve(".eagler-gui-self-test-missing").resolve("project");
        if (Files.exists(missingChild.getParent())
                || !directoryChooserStart(missingChild).equals(current)) {
            throw new IllegalStateException("directory chooser did not resolve an absent output to its existing parent");
        }
        Path typedChild = directoryChooserStart(current).resolve("project").normalize();
        if (Files.exists(typedChild)) {
            throw new IllegalStateException("directory chooser self-test destination unexpectedly exists");
        }
        try {
            newDirectoryPath(current, "../outside");
            throw new IllegalStateException("directory chooser accepted a folder name that escapes its parent");
        } catch (IllegalArgumentException expected) {
            // New output selection accepts one folder name only.
        }
    }

    private static void verifyContentProfileSelection() {
        Path root = null;
        try {
            root = Files.createTempDirectory("eagler-content-profile-self-test-");
            Path inputsDirectory = Files.createDirectories(root.resolve("inputs"));
            Files.createFile(root.resolve(NORMAL_CONTENT_PROFILE.patchBundleName()));
            Files.createFile(inputsDirectory.resolve(NORMAL_CONTENT_PROFILE.resourceOverlayName()));

            List<Path> searchDirectories = List.of(root, inputsDirectory);
            ProfileInputs normal = resolveProfileInputs(NORMAL_CONTENT_PROFILE, searchDirectories, root);
            ProfileInputs normalAgain = resolveProfileInputs(NORMAL_CONTENT_PROFILE, searchDirectories, root);
            if (!normal.patchBundle().equals(root.resolve("source-patch-bundle.zip"))
                    || !normal.patchSha256().equals(PatchEngine.acceptedBundleSha256())
                    || !normal.resourceOverlay().equals(inputsDirectory.resolve("resource-overlay-normal.zip"))
                    || !normal.resourceOverlaySha256().equals(ResourceOverlay.EXPECTED_ARCHIVE_SHA256)) {
                throw new IllegalStateException("Normal profile did not resolve its pinned input pair");
            }
            if (!normal.equals(normalAgain)
                    || !Arrays.equals(contentProfileNamesForMode(CREATE_DEV), new String[] {"Normal"})
                    || !Arrays.equals(contentProfileNamesForMode(BUILD_STANDALONE), new String[] {"Normal"})
                    || !Arrays.equals(contentProfileNamesForMode(BUILD_IWA), new String[] {"Normal"})) {
                throw new IllegalStateException("Normal profile is not the only available profile in every mode");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("content profile self-test could not create its input fixture", ex);
        } finally {
            if (root != null) {
                try {
                    deleteProfileFixture(root);
                } catch (IOException ex) {
                    throw new IllegalStateException("content profile self-test could not clean its fixture", ex);
                }
            }
        }
    }

    private static void deleteProfileFixture(Path root) throws IOException {
        Path inputsDirectory = root.resolve("inputs");
        Files.deleteIfExists(root.resolve(NORMAL_CONTENT_PROFILE.patchBundleName()));
        Files.deleteIfExists(inputsDirectory.resolve(NORMAL_CONTENT_PROFILE.resourceOverlayName()));
        Files.deleteIfExists(inputsDirectory);
        Files.deleteIfExists(root);
    }

    private static void verifyCommandArguments() {
        String bundleHash = "a".repeat(64);
        String skeletonHash = "b".repeat(64);
        String overlayHash = "c".repeat(64);
        String soundsHash = "d".repeat(64);
        String musicHash = "e".repeat(64);
        Map<String, String> values = Map.ofEntries(
                Map.entry("jar", "inputs/client.jar"),
                Map.entry("output", "out/project"),
                Map.entry("vineflower", "inputs/vineflower.jar"),
                Map.entry("java17", "tools/java17/bin/java"),
                Map.entry("patchBundle", "inputs/source-patch.zip"),
                Map.entry("patchSha", bundleHash),
                Map.entry("skeleton", "inputs/project-skeleton.zip"),
                Map.entry("skeletonSha", skeletonHash),
                Map.entry("resourceOverlay", "inputs/resource-overlay.zip"),
                Map.entry("resourceSha", overlayHash),
                Map.entry("externalRoot", "game/src/main/resources"),
                Map.entry("java25", "tools/java25/bin/java"),
                Map.entry("node", "tools/node/bin/node"),
                Map.entry("npm", "tools/node/lib/node_modules/npm/bin/npm-cli.js"),
                Map.entry("sounds", "inputs/sounds.epk"),
                Map.entry("soundsSha", soundsHash),
                Map.entry("musicEpk", "inputs/music.epk"),
                Map.entry("musicSha", musicHash),
                Map.entry("standaloneOutput", "out/game.html"),
                Map.entry("iwaOutput", "out/game.swbn"),
                Map.entry("iwaKey", "out/project/iwa/local-dev-key.pem"),
                Map.entry("musicPackOutput", "out/game-music-resource-pack.zip"),
                Map.entry("wispcraftScript", "wispcraft/dist/index.js"));

        List<String> source = buildCommandArguments(false, false, false, true, true, values);
        if (!"create-dev".equals(source.get(0))) {
            throw new IllegalStateException("create-dev command mode is invalid");
        }
        assertArgumentValue(source, "--expected-bundle-sha256", bundleHash);
        assertArgumentValue(source, "--expected-skeleton-sha256", skeletonHash);
        assertArgumentValue(source, "--expected-resource-overlay-sha256", overlayHash);
        assertOptionAbsent(source, "--with-music");
        assertOptionAbsent(source, "--music-pack-output");
        assertOptionAbsent(source, "--wispcraft-script");
        assertOptionAbsent(source, "--music-epk");
        assertOptionAbsent(source, "--expected-music-epk-sha256");
        assertOptionAbsent(source, "--reuse-project");

        List<String> externalMusic = buildCommandArguments(true, false, false, true, false, values);
        if (!"build-standalone".equals(externalMusic.get(0))) {
            throw new IllegalStateException("standalone command mode is invalid");
        }
        assertArgumentValue(externalMusic, "--expected-bundle-sha256", bundleHash);
        assertArgumentValue(externalMusic, "--expected-skeleton-sha256", skeletonHash);
        assertArgumentValue(externalMusic, "--expected-resource-overlay-sha256", overlayHash);
        assertArgumentValue(externalMusic, "--expected-sounds-epk-sha256", soundsHash);
        assertArgumentValue(externalMusic, "--music-epk",
                Path.of(values.get("musicEpk")).toAbsolutePath().normalize().toString());
        assertArgumentValue(externalMusic, "--expected-music-epk-sha256", musicHash);
        assertArgumentValue(externalMusic, "--music-pack-output",
                Path.of(values.get("musicPackOutput")).toAbsolutePath().normalize().toString());
        assertArgumentValue(externalMusic, "--wispcraft-script",
                Path.of(values.get("wispcraftScript")).toAbsolutePath().normalize().toString());
        assertOptionAbsent(externalMusic, "--with-music");
        assertOptionAbsent(externalMusic, "--reuse-project");

        List<String> reusedProject = buildCommandArguments(true, false, true, false, true, values);
        if (!"build-standalone".equals(reusedProject.get(0)) || !reusedProject.contains("--reuse-project")) {
            throw new IllegalStateException("standalone project reuse option is missing");
        }

        List<String> embeddedMusic = buildCommandArguments(true, false, false, false, true, values);
        if (!embeddedMusic.contains("--with-music")) {
            throw new IllegalStateException("embedded music option is missing from the standalone command");
        }
        assertArgumentValue(embeddedMusic, "--music-epk",
                Path.of(values.get("musicEpk")).toAbsolutePath().normalize().toString());
        assertArgumentValue(embeddedMusic, "--expected-music-epk-sha256", musicHash);
        assertOptionAbsent(embeddedMusic, "--music-pack-output");
        assertOptionAbsent(embeddedMusic, "--wispcraft-script");

        List<String> iwa = buildCommandArguments(false, true, false, true, true, values);
        if (!"build-iwa".equals(iwa.get(0))) {
            throw new IllegalStateException("IWA command mode is invalid");
        }
        assertArgumentValue(iwa, "--iwa-output",
                Path.of(values.get("iwaOutput")).toAbsolutePath().normalize().toString());
        assertArgumentValue(iwa, "--iwa-key",
                Path.of(values.get("iwaKey")).toAbsolutePath().normalize().toString());
        assertArgumentValue(iwa, "--standalone-output",
                Path.of(values.get("standaloneOutput")).toAbsolutePath().normalize().toString());
        assertOptionAbsent(iwa, "--with-music");
        assertOptionAbsent(iwa, "--music-pack-output");
        assertOptionAbsent(iwa, "--wispcraft-script");
        assertOptionAbsent(iwa, "--reuse-project");

        Map<String, String> defaultKeyValues = new LinkedHashMap<>(values);
        defaultKeyValues.put("iwaKey", "");
        List<String> iwaDefaultKey = buildCommandArguments(false, true, false, true, true, defaultKeyValues);
        assertOptionAbsent(iwaDefaultKey, "--iwa-key");
    }

    private static void verifyProgressContract() {
        ProgressUpdate decompile = parseProgress("PROGRESS stage=decompile elapsed_seconds=2");
        if (decompile == null || !"decompile".equals(decompile.stage()) || decompile.elapsedSeconds() != 2L) {
            throw new IllegalStateException("progress parser rejected a measured stage update");
        }
        if (parseProgress("PROGRESS stage=decompile elapsed_seconds=2 eta_seconds=148") != null) {
            throw new IllegalStateException("progress parser accepted a fabricated countdown");
        }
        ProgressUpdate complete = parseProgress("PROGRESS stage=complete elapsed_seconds=151");
        if (complete == null || !"complete".equals(complete.stage()) || complete.elapsedSeconds() != 151L) {
            throw new IllegalStateException("progress parser rejected completion");
        }
        if (!"decompile  |  2s elapsed".equals(progressStatus(decompile.stage(), decompile.elapsedSeconds()))) {
            throw new IllegalStateException("progress status includes unmeasured time remaining");
        }
    }

    private static void verifyPackagedMusicContract() {
        Path appDirectory = codeSource().getParent();
        if (appDirectory == null) {
            throw new IllegalStateException("patcher application directory could not be located");
        }
        Path input = appDirectory.resolve("inputs/music.epk");
        Path pin = input.resolveSibling("music.epk.sha256");
        if (!Files.exists(input) && !Files.exists(pin)) {
            try {
                packagedMusic();
                throw new IllegalStateException("missing packaged music input was accepted");
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage() == null || !ex.getMessage().contains("official client JAR contains no music")) {
                    throw ex;
                }
            }
            return;
        }
        PackagedMusic bundled = packagedMusic();
        if (!bundled.path().equals(input.toAbsolutePath().normalize())
                || !bundled.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("packaged music input or SHA-256 pin is invalid");
        }
    }

    private static void verifyAudioRunTriggerContract() {
        if (!shouldAutoPrepareAudio(true, false, true)
                || shouldAutoPrepareAudio(false, false, true)) {
            throw new IllegalStateException("missing-audio trigger mode contract is invalid");
        }
        if (shouldAutoPrepareAudio(true, true, true)
                || shouldAutoPrepareAudio(true, false, false)) {
            throw new IllegalStateException("audio trigger did not preserve explicit paths or existing outputs");
        }
        if (shouldStartAudioPreparation(true, false)
                || !shouldStartAudioPreparation(true, true)
                || shouldAdoptPreparedAudio(true)
                || !shouldAdoptPreparedAudio(false)) {
            throw new IllegalStateException("audio preflight or mid-flight edit contract is invalid");
        }
        Path blankJar = null;
        try {
            blankJar = Files.createTempFile("eagler-blank-client-", ".jar");
            String blankSha256 = sha256(blankJar);
            if (isExpectedSha256(Main.expectedClientJarSha256(), blankSha256)
                    || shouldStartAudioPreparation(true,
                            isExpectedSha256(Main.expectedClientJarSha256(), blankSha256))) {
                throw new IllegalStateException("blank official JAR could start audio preparation");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("blank official JAR preflight self-test failed", ex);
        } finally {
            if (blankJar != null) {
                try {
                    Files.deleteIfExists(blankJar);
                } catch (IOException ex) {
                    throw new IllegalStateException("blank official JAR preflight self-test cleanup failed", ex);
                }
            }
        }
        if (!shouldResumeRunAfterAudio(true, true, true)
                || shouldResumeRunAfterAudio(true, false, true)
                || shouldResumeRunAfterAudio(true, true, false)
                || shouldResumeRunAfterAudio(false, true, true)) {
            throw new IllegalStateException("audio resume success/error state contract is invalid");
        }
    }

    private static void verifyAudioPreparationContract() {
        Path fixture = null;
        try {
            fixture = Files.createTempDirectory("eagler-audio-gui-self-test-");
            Path cache = Files.createDirectories(fixture.resolve("inputs/audio-cache"));
            try {
                locateAudioToolsDirectory(fixture);
                throw new IllegalStateException("audio lookup borrowed tools outside the patcher kit");
            } catch (IOException expected) {
                // Installed kits must carry their own rebuild tools.
            }
            Path packagedAudioTools = Files.createDirectories(fixture.resolve("tools/audio/vendor/pako-2.1.0"));
            Files.createFile(packagedAudioTools.getParent().getParent().resolve("node_audio_rebuild.mjs"));
            Files.createFile(packagedAudioTools.getParent().getParent().resolve("audio-epk-metadata-26.2.json"));
            Files.createFile(packagedAudioTools.resolve("pako.min.js"));
            if (!locateAudioToolsDirectory(fixture).equals(packagedAudioTools.getParent().getParent())) {
                throw new IllegalStateException("audio lookup did not use the patcher kit's packaged tools/audio");
            }
            Path uniqueOutput = uniqueAudioRebuildOutput(cache);
            if (!uniqueOutput.getFileName().toString().startsWith("epk-rebuild-")
                    || Files.exists(uniqueOutput, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("audio rebuild output is not a unique absent directory");
            }
            Path node = fixture.resolve("node");
            Path script = fixture.resolve("node_audio_rebuild.mjs");
            Path metadata = fixture.resolve("audio-epk-metadata-26.2.json");
            Path index = cache.resolve("indexes/32.json");
            Path objects = cache.resolve("objects");
            Path ffmpeg = fixture.resolve("ffmpeg-6.1.1");
            Path pako = fixture.resolve("pako.min.js");
            List<String> command = audioRebuildCommand(node, script, metadata, index, objects, ffmpeg,
                    uniqueOutput, pako);
            if (!command.containsAll(List.of("--metadata", metadata.toAbsolutePath().normalize().toString(),
                    "--index", index.toAbsolutePath().normalize().toString(),
                    "--objects", objects.toAbsolutePath().normalize().toString(), "--ffmpeg",
                    ffmpeg.toAbsolutePath().normalize().toString(), "--output",
                    uniqueOutput.toAbsolutePath().normalize().toString(), "--group", "both"))) {
                throw new IllegalStateException("audio rebuild command omitted a pinned input or both-group selection");
            }

            Path receiptOutput = fixture.resolve("receipt-output").toAbsolutePath().normalize();
            Path receiptObjects = fixture.resolve("receipt-objects").toAbsolutePath().normalize();
            long startedMillis = System.currentTimeMillis();
            Map<String, Object> exactReceipt = audioReceiptFixture(receiptOutput, receiptObjects,
                    startedMillis / 1000L);
            Map<String, AudioReceiptArchive> accepted = validateAudioReceipt(
                    Json.parse(Json.canonical(exactReceipt), "self-test audio receipt"), receiptOutput,
                    receiptObjects, startedMillis);
            if (accepted.size() != 2 || !SOUNDS_ARCHIVE_SHA256.equals(accepted.get("sounds").sha256())
                    || !MUSIC_ARCHIVE_SHA256.equals(accepted.get("music").sha256())) {
                throw new IllegalStateException("audio receipt did not require both pinned archive hashes");
            }
            Map<String, Object> partialReceipt = new LinkedHashMap<>(exactReceipt);
            partialReceipt.put("status", "partial-fixture");
            expectAudioReceiptRejected(partialReceipt, receiptOutput, receiptObjects, startedMillis,
                    "partial fixture receipt");
            Map<String, Object> staleReceipt = new LinkedHashMap<>(exactReceipt);
            staleReceipt.put("started_unix", startedMillis / 1000L - 10L);
            expectAudioReceiptRejected(staleReceipt, receiptOutput, receiptObjects, startedMillis,
                    "stale audio receipt");

            Path publishSource = Files.createDirectories(fixture.resolve("rebuild"));
            Path publishTarget = Files.createDirectories(fixture.resolve("inputs"));
            List<AudioPublishFile> publishFiles = new ArrayList<>();
            for (String name : List.of("sounds.epk", "sounds.epk.sha256", "music.epk", "music.epk.sha256")) {
                Path source = publishSource.resolve(name);
                Files.writeString(source, "verified fixture " + name, StandardCharsets.UTF_8);
                publishFiles.add(new AudioPublishFile(source, publishTarget.resolve(name)));
            }
            publishAudioFilesNoOverwrite(publishFiles);
            for (AudioPublishFile file : publishFiles) {
                if (!Files.isSameFile(file.source(), file.target())) {
                    throw new IllegalStateException("audio publish did not atomically link its verified source");
                }
            }
            try {
                publishAudioFilesNoOverwrite(publishFiles);
                throw new IllegalStateException("audio publish overwrote an existing user file");
            } catch (IOException expected) {
                for (AudioPublishFile file : publishFiles) {
                    if (!Files.readString(file.target(), StandardCharsets.UTF_8)
                            .equals("verified fixture " + file.target().getFileName())) {
                        throw new IllegalStateException("audio publish changed an existing target", expected);
                    }
                }
            }

            Path fallbackRoot = Files.createDirectories(fixture.resolve("copy-publish"));
            Path fallbackSource = Files.createDirectories(fallbackRoot.resolve("rebuild"));
            Path fallbackTarget = Files.createDirectories(fallbackRoot.resolve("inputs"));
            List<AudioPublishFile> fallbackFiles = new ArrayList<>();
            for (String name : List.of("sounds.epk", "sounds.epk.sha256", "music.epk", "music.epk.sha256")) {
                Path source = fallbackSource.resolve(name);
                Files.writeString(source, "copied fixture " + name, StandardCharsets.UTF_8);
                fallbackFiles.add(new AudioPublishFile(source, fallbackTarget.resolve(name)));
            }
            publishAudioFilesNoOverwrite(fallbackFiles, false);
            for (AudioPublishFile file : fallbackFiles) {
                if (Files.isSameFile(file.source(), file.target())
                        || !Files.readString(file.target(), StandardCharsets.UTF_8)
                                .equals("copied fixture " + file.target().getFileName())) {
                    throw new IllegalStateException("audio publish copy fallback did not create verified independent files");
                }
            }
            Path raceRoot = Files.createDirectories(fixture.resolve("copy-publish-race"));
            Path raceSource = Files.createDirectories(raceRoot.resolve("rebuild"));
            Path raceTarget = Files.createDirectories(raceRoot.resolve("inputs"));
            List<AudioPublishFile> raceFiles = new ArrayList<>();
            for (String name : List.of("sounds.epk", "sounds.epk.sha256", "music.epk", "music.epk.sha256")) {
                Path source = raceSource.resolve(name);
                Files.writeString(source, "race fixture " + name, StandardCharsets.UTF_8);
                raceFiles.add(new AudioPublishFile(source, raceTarget.resolve(name)));
            }
            Path concurrentOwner = raceFiles.get(1).target();
            try {
                publishAudioFilesNoOverwrite(raceFiles, false, target -> {
                    if (target.equals(concurrentOwner)) {
                        Files.writeString(target, "concurrent owner", StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.CREATE_NEW,
                                java.nio.file.StandardOpenOption.WRITE);
                    }
                });
                throw new IllegalStateException("audio copy fallback replaced a concurrently created target");
            } catch (IOException expected) {
                if (!"concurrent owner".equals(Files.readString(concurrentOwner, StandardCharsets.UTF_8))) {
                    throw new IllegalStateException("audio copy fallback changed a concurrent target", expected);
                }
                Path firstTarget = raceFiles.get(0).target();
                if (Files.exists(firstTarget, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("audio copy fallback did not roll back its first published file",
                            expected);
                }
            }
            Path symlinkVictim = fallbackRoot.resolve("user-file.txt");
            Path symlinkTarget = fallbackTarget.resolve("music-user-file.epk");
            Files.writeString(symlinkVictim, "user data", StandardCharsets.UTF_8);
            boolean symlinkSupported = false;
            try {
                Files.createSymbolicLink(symlinkTarget, symlinkVictim);
                symlinkSupported = true;
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                // Some platforms disable symlink creation for unprivileged users.
            }
            if (symlinkSupported) {
                try {
                    publishAudioFilesNoOverwrite(List.of(new AudioPublishFile(fallbackFiles.get(0).source(),
                            symlinkTarget)));
                    throw new IllegalStateException("audio publish accepted a symlink output path");
                } catch (IOException expected) {
                    if (!Files.isSymbolicLink(symlinkTarget)
                            || !"user data".equals(Files.readString(symlinkVictim, StandardCharsets.UTF_8))) {
                        throw new IllegalStateException("audio publish changed a symlink target", expected);
                    }
                }
            }
            verifyAudioRebuildCancellation(fixture);
            System.out.println("audio process cleanup: captured descendant cancellation and surviving-child detection PASS");
            System.out.println("audio publication race: copy fallback preserved a concurrent no-overwrite target PASS");
        } catch (IOException ex) {
            throw new IllegalStateException("audio preparation self-test failed", ex);
        } finally {
            if (fixture != null) {
                try {
                    deleteAudioTestTree(fixture);
                } catch (IOException ex) {
                    throw new IllegalStateException("audio preparation self-test could not clean its fixture", ex);
                }
            }
        }
    }

    private static Map<String, Object> audioReceiptFixture(Path output, Path objects, long startedUnix) {
        Map<String, Object> sounds = audioReceiptGroupFixture(SOUNDS_ARCHIVE_SHA256, 4779L,
                output.resolve("sounds.epk").toString());
        Map<String, Object> music = audioReceiptGroupFixture(MUSIC_ARCHIVE_SHA256, 92L,
                output.resolve("music.epk").toString());
        return Map.ofEntries(
                Map.entry("status", "exact"),
                Map.entry("index_sha1", AUDIO_INDEX_SHA1),
                Map.entry("metadata_sha256", AUDIO_METADATA_SHA256),
                Map.entry("objects_root", objects.toString()),
                Map.entry("output", output.toString()),
                Map.entry("ffmpeg", "ffmpeg version 6.1.1 self-test"),
                Map.entry("started_unix", startedUnix),
                Map.entry("groups", Map.of("sounds", sounds, "music", music)));
    }

    private static Map<String, Object> audioReceiptGroupFixture(String sha256, long members, String archivePath) {
        return Map.ofEntries(
                Map.entry("status", "exact"),
                Map.entry("expected_archive_sha256", sha256),
                Map.entry("candidate_archive_sha256", sha256),
                Map.entry("archive_path", archivePath),
                Map.entry("expected_members", members),
                Map.entry("processed_members", members),
                Map.entry("remaining_members", 0L),
                Map.entry("archive_size", 1L));
    }

    private static void expectAudioReceiptRejected(Map<String, Object> receipt, Path output, Path objects,
            long startedMillis, String label) {
        try {
            validateAudioReceipt(Json.parse(Json.canonical(receipt), label), output, objects, startedMillis);
            throw new IllegalStateException(label + " was accepted");
        } catch (IOException expected) {
            // Fail-closed receipt conditions are the purpose of this fixture.
        }
    }

    private static void deleteAudioTestTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()))
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void verifyAudioRebuildCancellation(Path fixture) throws IOException {
        CountDownLatch processStarted = new CountDownLatch(1);
        AtomicReference<Process> child = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean cleanupConfirmed = new AtomicBoolean(true);
        List<String> command = List.of(javaExecutable().toString(), "-cp", codeSource().toString(),
                GuiMain.class.getName(), "--self-test-child");
        Thread worker = new Thread(() -> {
            try {
                runAudioRebuild(command, ignored -> { }, process -> {
                    child.set(process);
                    processStarted.countDown();
                }, cleanupConfirmed);
                failure.set(new IllegalStateException("cancel probe process exited without interruption"));
            } catch (IOException expected) {
                if (expected.getMessage() == null || !expected.getMessage().contains("interrupted")) {
                    failure.set(expected);
                }
            }
        }, "eagler-audio-cancel-self-test");
        worker.start();
        try {
            if (!processStarted.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("audio cancellation probe did not start its child process");
            }
            worker.interrupt();
            worker.join(8_000L);
            Process started = child.get();
            if (worker.isAlive() || started == null || started.isAlive() || !cleanupConfirmed.get()) {
                throw new IllegalStateException("audio cancellation did not confirm child process tree cleanup");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("audio cancellation self-test was interrupted", ex);
        } finally {
            Process started = child.get();
            if (started != null && started.isAlive()) terminateProcessTreeNow(started);
        }
        if (failure.get() != null) throw new IllegalStateException("audio cancellation self-test failed", failure.get());
        verifyAudioProcessTreeSurvivorDetection(fixture);
    }

    private static void verifyAudioProcessTreeSurvivorDetection(Path fixture) throws IOException {
        Path readyFile = fixture.resolve("audio-surviving-descendant-" + UUID.randomUUID() + ".pid");
        Process root = new ProcessBuilder(javaExecutable().toString(), "-cp", codeSource().toString(),
                GuiMain.class.getName(), "--self-test-child-with-descendant", readyFile.toString()).start();
        List<ProcessHandle> captured = new ArrayList<>();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8L);
            long descendantPid = -1L;
            while (root.isAlive() && System.nanoTime() < deadline) {
                if (Files.isRegularFile(readyFile, LinkOption.NOFOLLOW_LINKS)) {
                    String ready = Files.readString(readyFile, StandardCharsets.UTF_8).trim();
                    if (ready.matches("[0-9]+")) {
                        descendantPid = Long.parseLong(ready);
                        break;
                    }
                }
                Thread.sleep(40L);
            }
            if (descendantPid <= 0L) {
                throw new IllegalStateException("audio survivor probe did not report its descendant PID");
            }
            long expectedDescendantPid = descendantPid;
            captured = processTreeHandles(root);
            boolean descendantCaptured = captured.stream()
                    .anyMatch(handle -> handle.pid() == expectedDescendantPid && handle.isAlive());
            if (!descendantCaptured) {
                throw new IllegalStateException("audio survivor probe did not capture the live descendant handle");
            }
            root.destroyForcibly();
            if (!root.waitFor(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("audio survivor probe root process did not exit");
            }
            if (audioProcessTreeCleanupConfirmed(root, captured, true)) {
                throw new IllegalStateException("audio cleanup was confirmed while a captured descendant survived");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("audio process-tree survivor self-test was interrupted", error);
        } finally {
            if (root.isAlive()) {
                try {
                    terminateProcessTreeNow(root);
                } catch (RuntimeException ignored) {
                    // The postcondition below reports an unconfirmed fixture cleanup.
                }
            } else if (!captured.isEmpty()) {
                try {
                    terminateProcessTreeNow(captured);
                } catch (RuntimeException ignored) {
                    // The postcondition below reports an unconfirmed fixture cleanup.
                }
            }
            if (root.isAlive() || captured.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new IllegalStateException("audio survivor self-test could not clean up its process tree");
            }
            Files.deleteIfExists(readyFile);
        }
    }

    private static void assertArgumentValue(List<String> command, String option, String expected) {
        int index = command.indexOf(option);
        if (index < 0 || index + 1 >= command.size() || !expected.equals(command.get(index + 1))) {
            throw new IllegalStateException("command argument was not forwarded literally for " + option);
        }
    }

    private static void assertOptionAbsent(List<String> command, String option) {
        if (command.contains(option)) {
            throw new IllegalStateException("unexpected command option: " + option);
        }
    }
}
