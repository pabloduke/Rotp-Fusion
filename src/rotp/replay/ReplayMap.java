package rotp.replay;

import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.awt.font.FontRenderContext;
import java.io.IOException;

/** Fixed galaxy coordinates for both the interactive map and its movie. */
public final class ReplayMap {
    private static final Color SPACE = new Color(14, 18, 32);
    private static final Color UNCLAIMED = new Color(36, 42, 55);
    private final List<Path2D> regions = new ArrayList<>();
    private final List<ReplayFrame.Item> empires;
    private final double width, height;

    private final ReplayConflicts conflicts;
    private static final Font LEGEND_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 22);
    private static final FontRenderContext METRICS = new FontRenderContext(null, true, true);
    private static final Font IDENTITY_FONT = LEGEND_FONT.deriveFont(17f);
    private final java.util.Map<Integer,List<java.awt.font.TextLayout>> identityLines = new HashMap<>();
    private final List<ReplayRoster.Seat> roster;
    private final int legendWidth, legendHeight;

    public static ReplayMap from(ReplayArchive archive) throws IOException {
        return new ReplayMap(archive.frame(0), ReplayConflicts.read(archive), archive.roster());
    }

    ReplayMap(ReplayFrame initial, ReplayConflicts conflicts) {
        this(initial, conflicts, List.of());
    }

    ReplayMap(ReplayFrame initial, ReplayConflicts conflicts, List<ReplayRoster.Seat> roster) {
        this.roster = roster;
        this.conflicts = conflicts;
        var stars = initial.items().stream().filter(i -> i.kind().equals("System")).toList();
        empires = initial.items().stream().filter(i -> i.kind().equals("Empire")).toList();
        int systemCount = stars.size();
        double nameWidth = empires.stream().mapToDouble(e -> LEGEND_FONT.getStringBounds(e.name(), METRICS).getWidth()).max().orElse(0);
        double countWidth = LEGEND_FONT.getStringBounds(Integer.toString(systemCount), METRICS).getWidth();
        var labels = new HashMap<Integer,String>();
        for (var seat : roster) labels.put(seat.empireId(), seat.controllerLabel());
        double labelWidth = labels.values().stream()
                .mapToDouble(label -> IDENTITY_FONT.getStringBounds(label, METRICS).getWidth()).max().orElse(120);
        legendWidth = (int)Math.ceil(Math.max(Math.max(200, nameWidth + countWidth + 64), Math.min(440, labelWidth + 50)));
        int rowsHeight = 0;
        for (var empire : empires) {
            String label = labels.getOrDefault(empire.empire(), "Not recorded");
            var text = new java.text.AttributedString(label);
            text.addAttribute(java.awt.font.TextAttribute.FONT, IDENTITY_FONT);
            var wrapper = new java.awt.font.LineBreakMeasurer(text.getIterator(), METRICS);
            var lines = new ArrayList<java.awt.font.TextLayout>();
            while (wrapper.getPosition() < label.length()) lines.add(wrapper.nextLayout(legendWidth - 46));
            identityLines.put(empire.empire(), List.copyOf(lines));
            rowsHeight += 30 + 20 * lines.size();
        }
        legendHeight = 80 + rowsHeight;
        width = 2 + stars.stream().mapToDouble(ReplayFrame.Item::x).max().orElse(1);
        height = 2 + stars.stream().mapToDouble(ReplayFrame.Item::y).max().orElse(1);
        for (var star : stars) {
            List<Point2D.Double> vertices = List.of(new Point2D.Double(-1,-1), new Point2D.Double(width-1,-1),
                    new Point2D.Double(width-1,height-1), new Point2D.Double(-1,height-1));
            for (var other : stars) {
                if (star == other) continue;
                // Retain the half-plane on this star's side of the perpendicular bisector.
                double dx = other.x()-star.x(), dy = other.y()-star.y();
                double boundary = dx*(other.x()+star.x())/2 + dy*(other.y()+star.y())/2;
                vertices = clip(vertices, dx, dy, boundary);
            }
            var cell = new Path2D.Double();
            for (var vertex : vertices) {
                if (cell.getCurrentPoint() == null) cell.moveTo(vertex.x, vertex.y);
                else cell.lineTo(vertex.x, vertex.y);
            }
            if (!vertices.isEmpty()) cell.closePath();
            regions.add(cell);
        }
    }

    private static List<Point2D.Double> clip(List<Point2D.Double> polygon, double dx, double dy, double boundary) {
        var result = new ArrayList<Point2D.Double>();
        if (polygon.isEmpty()) return result;
        var previous = polygon.get(polygon.size()-1);
        double a = previous.x*dx + previous.y*dy - boundary;
        for (var next : polygon) {
            double b = next.x*dx + next.y*dy - boundary;
            if ((a <= 0) != (b <= 0)) {
                double fraction = a/(a-b);
                result.add(new Point2D.Double(previous.x + fraction*(next.x-previous.x),
                        previous.y + fraction*(next.y-previous.y)));
            }
            if (b <= 0) result.add(next);
            previous = next;
            a = b;
        }
        return result;
    }

    private float legendScale(int w, int h) {
        return Math.min(1f, Math.min(w * .45f / legendWidth, h / (float)legendHeight));
    }

    private int legendWidth(int w, int h) {
        return (int)Math.ceil(legendWidth * legendScale(w,h));
    }

    private AffineTransform projection(int w, int h, boolean territory) {
        int legend = territory ? legendWidth(w,h) : 0;
        double scale = Math.max(.01, Math.min((w-legend-24)/width, (h-24)/height));
        return new AffineTransform(scale, 0, 0, scale, (w-legend-width*scale)/2+scale, (h-height*scale)/2+scale);
    }

    public Point point(ReplayFrame.Item item, int w, int h, boolean territory) {
        var result = new Point();
        projection(w,h,territory).transform(new Point2D.Float(item.x(),item.y()),result);
        return result;
    }

    public void draw(Graphics2D g, int w, int h, ReplayFrame frame, boolean territory, ReplayFrame.Item selected) {
        draw(g, w, h, frame, territory, selected, Integer.MAX_VALUE);
    }

    public void draw(Graphics2D g, int w, int h, ReplayFrame frame, boolean territory, ReplayFrame.Item selected,
                     int frameIndex) {
        g.setColor(SPACE);
        g.fillRect(0,0,w,h);
        var transform = projection(w,h,territory);
        if (territory) {
            var counts = new HashMap<Integer,Integer>();
            int i = 0;
            for (var item : frame.items()) {
                if (!item.kind().equals("System")) continue;
                var region = transform.createTransformedShape(regions.get(i++));
                g.setColor(UNCLAIMED);
                g.fill(region);
                if (item.empire() >= 0) {
                    counts.merge(item.empire(), 1, Integer::sum);
                    Color fill = new Color(item.color()).darker();
                    g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 179));
                    g.fill(region);
                }
            }
            drawLegend(g, w, h, frame.turn(), counts);
        }
        var cues = territory ? conflicts.at(frame.turn(), frameIndex) : java.util.Map.<Integer, ReplayConflicts.Cue>of();
        int system = 0;
        for (var item : frame.items()) {
            if (item.kind().equals("Empire") || (territory && item.kind().equals("Fleet"))) continue;
            Point p = point(item,w,h,territory);
            g.setColor(territory ? Color.LIGHT_GRAY : new Color(item.color()));
            if (item.kind().equals("Fleet")) g.drawRect(p.x-5,p.y-5,10,10);
            else g.fillOval(p.x-2,p.y-2,5,5);
            if (item.kind().equals("System")) {
                var cue = cues.get(system++);
                if (cue != null) drawConflict(g, p, cue, h);
            }
            if (item.equals(selected)) {
                g.setColor(Color.WHITE);
                g.drawOval(p.x-8,p.y-8,16,16);
            }
        }
        var result = frame.result();
        if (territory && result != null)
            drawResult(g, w - legendWidth(w,h), h, attributedResult(result));
    }

    ReplayFrame.Result attributedResult(ReplayFrame.Result result) {
        var winners = roster.stream().filter(ReplayRoster.Seat::winner)
                .map(s -> s.empireName() + " (" + s.controllerLabel() + ")").toList();
        return winners.isEmpty() ? result : new ReplayFrame.Result(result.condition(), String.join(", ", winners));
    }

    /** Final-frame banner, confined to the map so the colony standings remain visible. */
    private static void drawResult(Graphics2D graphics, int availableWidth, int height, ReplayFrame.Result result) {
        if (availableWidth < 2 || height < 2) return;
        var g = (Graphics2D)graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = 800;
            int padding = 30;
            var titleFont = LEGEND_FONT.deriveFont(Font.BOLD, 36f);
            var nameFont = LEGEND_FONT.deriveFont(27f);
            String names = result.winners().isBlank() ? "No recorded winner" : "Won by " + result.winners();
            var text = new java.text.AttributedString(names);
            text.addAttribute(java.awt.font.TextAttribute.FONT, nameFont);
            var wrapper = new java.awt.font.LineBreakMeasurer(text.getIterator(), METRICS);
            var lines = new ArrayList<java.awt.font.TextLayout>();
            while (wrapper.getPosition() < names.length())
                lines.add(wrapper.nextLayout(width - 2 * padding));

            int boxHeight = 85 + 36 * lines.size();
            double scale = Math.min(1, Math.min(availableWidth * .92 / width, height * .8 / boxHeight));
            g.translate((availableWidth - width * scale) / 2, height * .94 - boxHeight * scale);
            g.scale(scale, scale);
            g.setColor(new Color(13, 19, 31, 240));
            g.fillRoundRect(0, 0, width, boxHeight, 16, 16);
            g.setColor(new Color(255, 208, 105));
            g.setFont(titleFont);
            g.drawString(result.condition(), padding, 50);
            g.setColor(Color.WHITE);
            int baseline = 90;
            for (var line : lines) {
                line.draw(g, padding, baseline);
                baseline += 36;
            }
        } finally { g.dispose(); }
    }

    private void drawLegend(Graphics2D g, int w, int h, int turn, java.util.Map<Integer,Integer> counts) {
        var panel = (Graphics2D)g.create();
        try {
            panel.translate(w - legendWidth(w,h), 0);
            float scale = legendScale(w,h);
            panel.scale(scale, scale);
            panel.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            panel.setColor(Color.WHITE);
            panel.setFont(LEGEND_FONT.deriveFont(Font.BOLD));
            panel.drawString("Turn " + turn,12,30);
            int right = legendWidth - 12;
            panel.setFont(LEGEND_FONT.deriveFont(15f));
            panel.drawString("Colonies",right-panel.getFontMetrics().stringWidth("Colonies"),55);
            panel.setFont(LEGEND_FONT);
            int y = 80;
            for (var empire : empires) {
                panel.setColor(new Color(empire.color()));
                panel.fillRect(12,y-12,12,12);
                panel.setColor(Color.WHITE);
                panel.drawString(empire.name(),34,y);
                String count = Integer.toString(counts.getOrDefault(empire.empire(),0));
                panel.drawString(count,right-panel.getFontMetrics().stringWidth(count),y);
                panel.setColor(new Color(204, 212, 227));
                for (var line : identityLines.get(empire.empire())) {
                    y += 20;
                    line.draw(panel, 34, y);
                }
                y += 30;
            }
        } finally { panel.dispose(); }
    }

    private static void drawConflict(Graphics2D graphics, Point point, ReplayConflicts.Cue cue, int height) {
        var g = (Graphics2D)graphics.create();
        try {
            g.translate(point.x, point.y);
            double scale = Math.max(.6, height / 1080.0);
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float alpha = 1f - cue.age() / (float)ReplayConflicts.VISIBLE_TURNS;
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));
            for (int r = 10; r <= (cue.capture() ? 17 : 10); r += 7) {
                g.setStroke(new BasicStroke(5));
                g.setColor(new Color(42,29,14));
                g.drawOval(-r,-r,2*r,2*r);
                g.setStroke(new BasicStroke(2.5f));
                g.setColor(new Color(255,208,105));
                g.drawOval(-r,-r,2*r,2*r);
            }
        } finally { g.dispose(); }
    }
}
