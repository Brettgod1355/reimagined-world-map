package com.hdmapreforged;

import lombok.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.function.*;

/** The custom routes button above the floor buttons: toggles the custom routes panel. Swing thread only. */
@RequiredArgsConstructor
final class RoutesButton implements MapView.Widget
{
    private static final int LEFT = 12;
    private static final Color OPEN = new Color(60, 66, 44, 235);
    private static final Color ROUTE = new Color(255, 214, 64);

    private final Runnable toggle;
    private final BooleanSupplier open;
    private final BooleanSupplier shown;

    @Override
    public void paint(Graphics2D g, int width, int height, int bottom, MapView.Clicks clicks)
    {
        if (!shown.getAsBoolean())
        {
            return;
        }
        g.setFont(MapView.CONTROL_FONT);
        FontMetrics metrics = g.getFontMetrics();
        int w = MapView.floorGroupWidth(metrics);
        RoundRectangle2D button = new RoundRectangle2D.Double(LEFT, bottom - MapView.CONTROL, w, MapView.CONTROL, 10, 10);
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Double(LEFT + 1, bottom - MapView.CONTROL + 2, w, MapView.CONTROL, 10, 10));
        g.setColor(open.getAsBoolean() ? OPEN : clicks.hovered(button) ? MapView.CONTROL_HOVER : MapView.CONTROL_FILL);
        g.fill(button);
        g.setColor(MapView.CONTROL_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(button);
        // Three stops joined by a dashed line, then the word.
        int textWidth = metrics.stringWidth("Routes");
        double x = LEFT + (w - textWidth - 20) / 2.0;
        double y = bottom - MapView.CONTROL / 2.0;
        Path2D path = new Path2D.Double();
        path.moveTo(x, y + 5);
        path.lineTo(x + 6, y - 5);
        path.lineTo(x + 12, y + 3);
        g.setColor(ROUTE);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2.5f, 2f}, 0));
        g.draw(path);
        g.setColor(Color.WHITE);
        for (double[] p : new double[][]{{x, y + 5}, {x + 6, y - 5}, {x + 12, y + 3}})
        {
            g.fill(new Ellipse2D.Double(p[0] - 2, p[1] - 2, 4, 4));
        }
        g.drawString("Routes", (float) (x + 20), (float) (y + metrics.getAscent() / 2.0 - 2));
        clicks.add(button, toggle, "Custom routes: your own routes with several stops, to order, save and run");
    }
}
