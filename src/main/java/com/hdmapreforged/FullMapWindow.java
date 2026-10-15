package com.hdmapreforged;

import static java.awt.Cursor.*;
import static java.lang.Math.*;

import java.awt.*;
import java.awt.Point;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.text.*;
import lombok.*;
import net.runelite.api.*;

/**
 * The map over the game view: a window of its own, owned by the client's (RuneLite's own window is never changed).
 * Not drawn in the game because Stretched Mode would blur it. Takes the keyboard only while its search is typed in;
 * never moves, resizes or focuses the client window. Swing thread only.
 */
final class FullMapWindow implements MapView.WindowControls
{
    static final int GRIP_HEIGHT = 10;
    static final Color GRIP_DOTS = new Color(255, 255, 255, 90);
    static final int EDGE = 8;
    /** Thicker than the sides: easy to take hold of. */
    static final int BOTTOM = 12;
    static final int MIN_WIDTH = 320;
    static final int MIN_HEIGHT = 240;
    static final Color FRAME = new Color(30, 32, 37);
    static final Color FRAME_EDGE = new Color(255, 255, 255, 40);
    /** By the edges a point is on (see edgesAt). */
    static final int[] CURSORS = {DEFAULT_CURSOR, W_RESIZE_CURSOR, E_RESIZE_CURSOR,
        DEFAULT_CURSOR, N_RESIZE_CURSOR, NW_RESIZE_CURSOR, NE_RESIZE_CURSOR,
        DEFAULT_CURSOR, S_RESIZE_CURSOR, SW_RESIZE_CURSOR, SE_RESIZE_CURSOR};

    final Client client;
    final MapScreen screen;
    final Consumer<String> saveBounds;
    final Predicate<KeyEvent> isMapKey;
    JWindow window;
    Canvas watchedCanvas;
    Window watchedFrame;
    /** Fractions of the game view, or null for the default. */
    Rectangle2D relative;
    boolean maximised;
    final ComponentListener follower = new ComponentAdapter()
    {
        @Override
        public void componentMoved(ComponentEvent e)
        {
            place();
        }

        @Override
        public void componentResized(ComponentEvent e)
        {
            place();
        }

        @Override
        public void componentHidden(ComponentEvent e)
        {
            close();
        }
    };

    FullMapWindow(Client client, MapScreen screen, String savedBounds, Consumer<String> saveBounds,
        Predicate<KeyEvent> isMapKey)
    {
        this.client = client;
        this.screen = screen;
        this.saveBounds = saveBounds;
        this.isMapKey = isMapKey;
        screen.setWindowControls(this);
        var decoded = decode(savedBounds);
        if (decoded != null)
        {
            relative = (Rectangle2D) decoded[0];
            maximised = (Boolean) decoded[1];
        }
        screen.setScreenLayout(MapScreen.Layout.FULL);
        screen.view().addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseReleased(MouseEvent e)
            {
                SwingUtilities.invokeLater(FullMapWindow.this::giveKeysBack);
            }
        });
    }

    @Override
    public boolean isMaximised()
    {
        return maximised;
    }

    boolean isOpen()
    {
        return window != null && window.isVisible();
    }

    void setHostBounds(Rectangle screenBounds)
    {
        window.setBounds(screenBounds);
        window.validate();
    }

    /** Whether the map's window has the keyboard. */
    boolean hasKeys()
    {
        return window != null && window.getFocusOwner() != null;
    }

    static boolean menuOpen()
    {
        return MenuSelectionManager.defaultManager().getSelectedPath().length > 0;
    }

    static Rectangle onScreen(Component c)
    {
        return new Rectangle(c.getLocationOnScreen(), c.getSize());
    }

    void toggle()
    {
        if (isOpen())
        {
            close();
        }
        else
        {
            open();
        }
    }

    void open()
    {
        Canvas canvas = client.getCanvas();
        if (canvas == null || !canvas.isShowing())
        {
            return;
        }
        if (window == null)
        {
            window = new JWindow(SwingUtilities.getWindowAncestor(canvas));
            // A utility window: left out of the taskbar and alt-tab, raised and lowered with the game.
            window.setType(Window.Type.UTILITY);
            // Some window managers still focus it on click; then Escape and the map key must work here too.
            window.setAutoRequestFocus(false);
            // Never focusable, so the desktop (KWin) keeps the game active; only while typing (see typable).
            window.setFocusableWindowState(false);
            window.addWindowFocusListener(new WindowAdapter()
            {
                @Override
                public void windowLostFocus(WindowEvent e)
                {
                    window.setFocusableWindowState(false);
                }
            });
            window.setContentPane(frame());
        }
        watch(canvas);
        place();
        if (!window.isVisible())
        {
            window.setVisible(true);
            // Some window managers place a new window themselves.
            SwingUtilities.invokeLater(this::place);
            screen.opened();
        }
    }

    @Override
    public void close()
    {
        if (window != null && window.isVisible())
        {
            // No longer focusable, a focused window hands the keyboard back to the game's (setFocusableWindowState).
            window.setFocusableWindowState(false);
            window.setVisible(false);
        }
    }

    @Override
    public void toggleMaximised()
    {
        maximised = !maximised;
        save();
        place();
        screen.refresh();
    }

    /**
     * Escape and the map key close the map while one of its parts has the keyboard (the game sees no keys then). While
     * typing or in a menu, keys are theirs: Escape closes the menu, letters type.
     */
    boolean closeKey(KeyEvent e)
    {
        Object owner = e.getSource();
        if (!isOpen() || menuOpen() || owner instanceof JTextComponent && (e.getKeyCode() != KeyEvent.VK_ESCAPE
            || !((JTextComponent) owner).getText().isEmpty()))
        {
            return false;
        }
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE || isMapKey.test(e))
        {
            close();
            return true;
        }
        return false;
    }

    /** In a window kept unfocusable (see open), a click in {@code field} lets the window take the keyboard to type. */
    static void typable(JTextComponent field)
    {
        field.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                Window host = SwingUtilities.getWindowAncestor(field);
                if (host != null && !host.getFocusableWindowState())
                {
                    host.setFocusableWindowState(true);
                    SwingUtilities.invokeLater(field::requestFocus);
                }
            }
        });
    }

    /** After a click on the map itself, the keyboard goes back to the game. */
    void giveKeysBack()
    {
        Canvas canvas = watchedCanvas;
        if (isOpen() && hasKeys() && !menuOpen() && canvas != null && canvas.isShowing())
        {
            window.setFocusableWindowState(false);
        }
    }

    JPanel frame()
    {
        JPanel root = new JPanel(new BorderLayout())
        {
            @Override
            protected boolean processKeyBinding(KeyStroke ks, KeyEvent e, int condition, boolean pressed)
            {
                return pressed && closeKey(e) || super.processKeyBinding(ks, e, condition, pressed);
            }

            @Override
            protected void paintComponent(Graphics graphics)
            {
                super.paintComponent(graphics);
                var g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(GRIP_DOTS);
                int right = getWidth() - 3;
                int bottom = getHeight() - 3;
                for (int row = 0; row < 3; row++)
                {
                    for (int col = 0; col <= row; col++)
                    {
                        g.fillOval(right - col * 4 - 2, bottom - (row - col) * 4 - 2, 2, 2);
                    }
                }
                g.dispose();
            }
        };
        root.setBackground(FRAME);
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(FRAME_EDGE),
            BorderFactory.createEmptyBorder(0, EDGE - 1, BOTTOM - 1, EDGE - 1)));
        root.add(new Grip(), BorderLayout.NORTH);
        root.add(screen, BorderLayout.CENTER);
        var resizer = new Resizer(root);
        root.addMouseListener(resizer);
        root.addMouseMotionListener(resizer);
        return root;
    }

    void place()
    {
        Canvas canvas = watchedCanvas;
        if (window == null || canvas == null)
        {
            return;
        }
        if (!canvas.isShowing())
        {
            close();
            return;
        }
        Rectangle view = onScreen(canvas);
        Rectangle bounds = maximised ? view : fit(relative, view);
        if (!window.getBounds().equals(bounds))
        {
            setHostBounds(bounds);
        }
    }

    /** Screen bounds for fractions of the game view (null: the default), inside the view, at least the minimum size. */
    static Rectangle fit(Rectangle2D relative, Rectangle view)
    {
        double rx = relative != null ? relative.getX() : 0.08;
        double ry = relative != null ? relative.getY() : 0.06;
        double rw = relative != null ? relative.getWidth() : 0.84;
        double rh = relative != null ? relative.getHeight() : 0.88;
        int width = (int) round(rw * view.width);
        int height = (int) round(rh * view.height);
        width = min(view.width, max(min(MIN_WIDTH, view.width), width));
        height = min(view.height, max(min(MIN_HEIGHT, view.height), height));
        int x = view.x + (int) round(rx * view.width);
        int y = view.y + (int) round(ry * view.height);
        x = max(view.x, min(view.x + view.width - width, x));
        y = max(view.y, min(view.y + view.height - height, y));
        return new Rectangle(x, y, width, height);
    }

    void setFromScreen(Rectangle bounds)
    {
        Canvas canvas = watchedCanvas;
        if (canvas == null || !canvas.isShowing() || canvas.getWidth() <= 0 || canvas.getHeight() <= 0)
        {
            return;
        }
        Rectangle view = onScreen(canvas);
        Rectangle fitted = fit(fraction(bounds, view), view);
        relative = fraction(fitted, view);
        maximised = false;
        if (!window.getBounds().equals(fitted))
        {
            setHostBounds(fitted);
        }
    }

    /** Screen bounds as fractions of the game view. */
    static Rectangle2D fraction(Rectangle r, Rectangle view)
    {
        return new Rectangle2D.Double((r.x - view.x) / (double) view.width, (r.y - view.y) / (double) view.height,
            r.width / (double) view.width, r.height / (double) view.height);
    }

    void save()
    {
        saveBounds.accept(encode(relative, maximised));
    }

    static String encode(Rectangle2D relative, boolean maximised)
    {
        if (relative == null)
        {
            return maximised ? "max" : "";
        }
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f%s", relative.getX(), relative.getY(), relative.getWidth(),
            relative.getHeight(), maximised ? ",max" : "");
    }

    /** {bounds or null, maximised}, or null when nothing usable was saved. */
    static Object[] decode(String saved)
    {
        if (saved == null || saved.trim().isEmpty())
        {
            return null;
        }
        var parts = saved.trim().split(",");
        if (parts.length == 1 && "max".equals(parts[0]))
        {
            return new Object[]{null, true};
        }
        if (parts.length != 4 && parts.length != 5)
        {
            return null;
        }
        try
        {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double w = Double.parseDouble(parts[2]);
            double h = Double.parseDouble(parts[3]);
            if (!(w > 0.05 && w <= 1 && h > 0.05 && h <= 1 && x >= -1 && x <= 1 && y >= -1 && y <= 1))
            {
                return null;
            }
            return new Object[]{new Rectangle2D.Double(x, y, w, h), parts.length == 5 && "max".equals(parts[4])};
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    void watch(Canvas canvas)
    {
        if (canvas == watchedCanvas)
        {
            return;
        }
        unwatch();
        watchedCanvas = canvas;
        canvas.addComponentListener(follower);
        watchedFrame = SwingUtilities.getWindowAncestor(canvas);
        if (watchedFrame != null)
        {
            watchedFrame.addComponentListener(follower);
        }
    }

    void unwatch()
    {
        if (watchedCanvas != null)
        {
            watchedCanvas.removeComponentListener(follower);
        }
        if (watchedFrame != null)
        {
            watchedFrame.removeComponentListener(follower);
        }
        watchedCanvas = null;
        watchedFrame = null;
    }

    void dispose()
    {
        close();
        unwatch();
        if (window != null)
        {
            window.dispose();
            window = null;
        }
    }

    /** A mouse drag that moves or resizes the map; saved once let go. */
    private abstract class Drag extends MouseAdapter
    {
        Point grab;
        Rectangle start;

        void grab(MouseEvent e)
        {
            grab = e.getLocationOnScreen();
            start = window.getBounds();
        }

        @Override
        public void mouseReleased(MouseEvent e)
        {
            if (grab != null)
            {
                grab = null;
                save();
            }
        }
    }

    final class Grip extends JComponent
    {
        Grip()
        {
            setPreferredSize(new Dimension(100, GRIP_HEIGHT));
            setCursor(Cursor.getPredefinedCursor(MOVE_CURSOR));
            setToolTipText("Drag to move; double-click to fill the game view");
            Drag mouse = new Drag()
            {
                @Override
                public void mousePressed(MouseEvent e)
                {
                    if (SwingUtilities.isLeftMouseButton(e))
                    {
                        grab(e);
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e)
                {
                    if (grab == null)
                    {
                        return;
                    }
                    if (maximised)
                    {
                        Canvas canvas = watchedCanvas;
                        if (canvas == null || !canvas.isShowing())
                        {
                            return;
                        }
                        // Dragging a maximised window restores it under the mouse.
                        Rectangle restored = fit(relative, onScreen(canvas));
                        double along = (grab.x - start.x) / (double) max(1, start.width);
                        start = new Rectangle(grab.x - (int) (restored.width * along), start.y, restored.width, restored.height);
                        maximised = false;
                    }
                    Point now = e.getLocationOnScreen();
                    setFromScreen(new Rectangle(start.x + now.x - grab.x, start.y + now.y - grab.y, start.width, start.height));
                }

                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (e.getClickCount() == 2)
                    {
                        toggleMaximised();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(FRAME);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(GRIP_DOTS);
            double cx = getWidth() / 2.0;
            double cy = getHeight() / 2.0;
            for (int i = -3; i <= 3; i++)
            {
                g.fill(new Ellipse2D.Double(cx + i * 7 - 1.5, cy - 1.5, 3, 3));
            }
            g.dispose();
        }
    }

    @RequiredArgsConstructor
    final class Resizer extends Drag
    {
        final JComponent root;
        int edges;

        /** Bits: 1 left, 2 right, 4 top, 8 bottom. */
        int edgesAt(Point p)
        {
            int corner = EDGE * 4;
            int w = root.getWidth();
            int h = root.getHeight();
            return (p.x < EDGE || p.x < corner && p.y >= h - corner ? 1 : 0)
                | (p.x >= w - EDGE || p.x >= w - corner && p.y >= h - corner ? 2 : 0)
                | (p.y < 3 ? 4 : 0)
                | (p.y >= h - BOTTOM || p.y >= h - corner && (p.x < corner || p.x >= w - corner) ? 8 : 0);
        }

        @Override
        public void mouseMoved(MouseEvent e)
        {
            int at = edgesAt(e.getPoint());
            root.setCursor(Cursor.getPredefinedCursor(at < CURSORS.length ? CURSORS[at] : DEFAULT_CURSOR));
        }

        @Override
        public void mouseExited(MouseEvent e)
        {
            if (grab == null)
            {
                root.setCursor(Cursor.getDefaultCursor());
            }
        }

        @Override
        public void mousePressed(MouseEvent e)
        {
            edges = edgesAt(e.getPoint());
            if (edges != 0 && SwingUtilities.isLeftMouseButton(e))
            {
                grab(e);
            }
        }

        @Override
        public void mouseDragged(MouseEvent e)
        {
            if (grab == null)
            {
                return;
            }
            Point now = e.getLocationOnScreen();
            int dx = now.x - grab.x;
            int dy = now.y - grab.y;
            Rectangle r = new Rectangle(start);
            if ((edges & 1) != 0)
            {
                int width = max(MIN_WIDTH, start.width - dx);
                r.x = start.x + start.width - width;
                r.width = width;
            }
            if ((edges & 2) != 0)
            {
                r.width = max(MIN_WIDTH, start.width + dx);
            }
            if ((edges & 4) != 0)
            {
                int height = max(MIN_HEIGHT, start.height - dy);
                r.y = start.y + start.height - height;
                r.height = height;
            }
            if ((edges & 8) != 0)
            {
                r.height = max(MIN_HEIGHT, start.height + dy);
            }
            setFromScreen(r);
        }
    }
}
