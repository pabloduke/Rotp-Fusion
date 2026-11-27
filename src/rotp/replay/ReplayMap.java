package rotp.replay;

import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/** Fixed galaxy coordinates for both the interactive map and its movie. */
public final class ReplayMap {
    private static final Color SPACE = new Color(14, 18, 32);
    private static final Color UNCLAIMED = new Color(36, 42, 55);
    private final List<Path2D> regions = new ArrayList<>();
    private final List<ReplayFrame.Item> empires;
    private final double width, height;

    public ReplayMap(ReplayFrame initial) {
        var stars = initial.items().stream().filter(i -> i.kind().equals("System")).toList();
        empires = initial.items().stream().filter(i -> i.kind().equals("Empire")).toList();
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

    private AffineTransform projection(int w, int h, boolean territory) {
        int legend = territory ? Math.min(w/3, 240) : 0;
        double scale = Math.max(.01, Math.min((w-legend-24)/width, (h-24)/height));
        return new AffineTransform(scale, 0, 0, scale, (w-legend-width*scale)/2+scale, (h-height*scale)/2+scale);
    }

    public Point point(ReplayFrame.Item item, int w, int h, boolean territory) {
        var result = new Point();
        projection(w,h,territory).transform(new Point2D.Float(item.x(),item.y()),result);
        return result;
    }

    public void draw(Graphics2D g, int w, int h, ReplayFrame frame, boolean territory, ReplayFrame.Item selected) {
        g.setColor(SPACE);
        g.fillRect(0,0,w,h);
        var transform = projection(w,h,territory);
        if (territory) {
            int i = 0;
            for (var item : frame.items()) {
                if (!item.kind().equals("System")) continue;
                g.setColor(item.empire() < 0 ? UNCLAIMED : new Color(item.color()).darker());
                g.fill(transform.createTransformedShape(regions.get(i++)));
            }
            int x = w - Math.min(w/3,240) + 8;
            int font = Math.max(10, Math.min(22,(h-50)/Math.max(1,empires.size()+1)-4));
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,font));
            g.setColor(Color.WHITE);
            g.drawString("Turn " + frame.turn(),x,font+12);
            int y = font*2+24;
            for (var empire : empires) {
                g.setColor(new Color(empire.color()));
                g.fillRect(x,y-font+3,font-2,font-2);
                g.setColor(Color.WHITE);
                g.drawString(empire.name(),x+font+4,y);
                y += font+4;
            }
        }
        for (var item : frame.items()) {
            if (item.kind().equals("Empire") || (territory && item.kind().equals("Fleet"))) continue;
            Point p = point(item,w,h,territory);
            g.setColor(territory ? Color.LIGHT_GRAY : new Color(item.color()));
            if (item.kind().equals("Fleet")) g.drawRect(p.x-5,p.y-5,10,10);
            else g.fillOval(p.x-2,p.y-2,5,5);
            if (item.equals(selected)) {
                g.setColor(Color.WHITE);
                g.drawOval(p.x-8,p.y-8,16,16);
            }
        }
    }
}
