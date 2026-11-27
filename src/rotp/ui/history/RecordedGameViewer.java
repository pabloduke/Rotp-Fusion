package rotp.ui.history;

import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import rotp.replay.ReplayArchive;
import rotp.replay.ReplayFrame;

/** Read-only observer window; all access to the archive is confined to the reader thread. */
public final class RecordedGameViewer extends JDialog {
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final JButton previous = new JButton("Previous turn"), next = new JButton("Next turn");
    private final JButton play = new JButton("Play");
    private final JLabel status = new JLabel("Opening recording…");
    private final JComboBox<String> speed = new JComboBox<>(new String[]{"0.5×", "1×", "2×", "4×"});
    private final Timer timer = new Timer(1000, e -> load(this.index + 1));
    private final JList<ReplayFrame.Item> objects = new JList<>();
    private final JList<ReplayFrame.Event> events = new JList<>();
    private final JTextArea details = new JTextArea(5, 36);
    private final GalaxyMap map = new GalaxyMap();
    private final JCheckBox territory = new JCheckBox("Territory");
    private rotp.replay.ReplayMap renderer;
    private ReplayArchive archive;
    private ReplayFrame frame;
    private int index = -1, count;
    private boolean busy, closed, playing;

    public static void open(Component parent) {
        var chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Recorded galaxy (*.rotpreplay)", "rotpreplay"));
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)
            new RecordedGameViewer(parent, chooser.getSelectedFile().toPath()).setVisible(true);
    }

    RecordedGameViewer(Component parent, Path file) {
        super(SwingUtilities.getWindowAncestor(parent), "Replay — " + file.getFileName(),
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component control : new Component[]{previous, play, next, speed, territory, status}) controls.add(control);
        add(controls, BorderLayout.NORTH);
        var tabs = new JTabbedPane();
        tabs.addTab("Galaxy objects", new JScrollPane(objects));
        tabs.addTab("Turn journal", new JScrollPane(events));
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        var inspector = new JPanel(new BorderLayout());
        inspector.add(tabs);
        inspector.add(new JScrollPane(details), BorderLayout.SOUTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, map, inspector);
        split.setResizeWeight(0.64);
        add(split);
        objects.addListSelectionListener(e -> inspectObject());
        events.addListSelectionListener(e -> inspectEvent());
        map.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (frame == null) return;
                double nearest = 14;
                ReplayFrame.Item hit = null;
                for (var item : frame.items()) {
                    if (item.kind().equals("Empire")) continue;
                    if (territory.isSelected() && item.kind().equals("Fleet")) continue;
                    double distance = renderer.point(item,map.getWidth(),map.getHeight(),territory.isSelected()).distance(e.getPoint());
                    if (distance <= nearest) { nearest = distance; hit = item; }
                }
                if (hit != null) {
                    tabs.setSelectedIndex(0);
                    objects.setSelectedValue(hit, true);
                    inspectObject();
                }
            }
        });
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 0) inspectObject();
            else inspectEvent();
        });
        territory.addActionListener(e -> map.repaint());
        previous.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));
        play.addActionListener(e -> { playing = !playing; refreshControls(); });
        timer.setRepeats(false);
        speed.setSelectedIndex(1);
        speed.addActionListener(e -> {
            timer.setInitialDelay(new int[]{2000, 1000, 500, 250}[speed.getSelectedIndex()]);
            refreshControls();
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                closed = true;
                timer.stop();
                reader.execute(() -> {
                    if (archive != null) try { archive.close(); } catch (IOException ignored) { }
                });
                reader.shutdown();
            }
        });
        setSize(1000, 650);
        setLocationRelativeTo(parent);
        busy = true;
        refreshControls();
        reader.execute(() -> {
            try {
                archive = new ReplayArchive(file);
                int size = archive.size();
                var initial = archive.frame(0);
                var layout = rotp.replay.ReplayMap.from(archive);
                SwingUtilities.invokeLater(() -> { renderer = layout; count = size; display(0, initial); });
            } catch (Exception failure) { failed(failure); }
        });
    }

    private void inspectEvent() {
        var event = events.getSelectedValue();
        details.setText(event == null ? "" : event.kind() + " · Turn " + event.turn() + " · " + event.phase()
                + (event.empire() < 0 ? "" : "\nEmpire #" + event.empire())
                + (event.system() < 0 ? "" : "  System #" + event.system()) + "\n" + event.details());
    }

    private void inspectObject() {
        var item = objects.getSelectedValue();
        details.setText(item == null ? "" : item.kind() + " · " + item.name() + "\n" + item.details());
        map.repaint();
    }

    private void step(int by) {
        playing = false;
        load(index + by);
    }

    private void refreshControls() {
        timer.stop();
        previous.setEnabled(!busy && index > 0);
        next.setEnabled(!busy && index + 1 < count);
        play.setEnabled(playing || next.isEnabled());
        play.setText(playing ? "Pause" : "Play");
        if (playing && !busy) timer.restart();
    }

    private void load(int requested) {
        if (busy || closed || requested < 0 || requested >= count) return;
        busy = true;
        refreshControls();
        reader.execute(() -> {
            try {
                var loaded = archive.frame(requested);
                SwingUtilities.invokeLater(() -> display(requested, loaded));
            } catch (Exception failure) { failed(failure); }
        });
    }

    private void failed(Exception failure) {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            JOptionPane.showMessageDialog(this, failure.getMessage(), "Cannot read replay", JOptionPane.ERROR_MESSAGE);
            dispose();
        });
    }

    private void display(int position, ReplayFrame loaded) {
        if (closed) return;
        frame = loaded;
        index = position;
        busy = false;
        if (index == count - 1) playing = false;
        var result = frame.result();
        status.setText("Turn " + frame.turn() + (result == null ? "" : "  " + result.summary()));
        objects.setListData(frame.items().toArray(ReplayFrame.Item[]::new));
        events.setListData(frame.events().toArray(ReplayFrame.Event[]::new));
        details.setText("");
        map.repaint();
        refreshControls();
    }

    private final class GalaxyMap extends JPanel {
        GalaxyMap() {
            setPreferredSize(new Dimension(600, 450));
            setBackground(new Color(14, 18, 32));
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (frame != null)
                renderer.draw((Graphics2D)graphics,getWidth(),getHeight(),frame,territory.isSelected(),objects.getSelectedValue());
        }
    }
}
