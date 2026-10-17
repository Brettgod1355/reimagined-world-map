package com.hdmapreforged;

import java.awt.*;
import javax.swing.*;

/** Takes the scroll pane's width; scrolls only up and down. */
class Fitted extends JPanel implements Scrollable
{
    /** What a page's scroll keeps in view of the page before. */
    final int overlap;

    Fitted(LayoutManager layout, int overlap)
    {
        super(layout);
        this.overlap = overlap;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize()
    {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
    {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
    {
        return visible.height - overlap;
    }

    @Override
    public boolean getScrollableTracksViewportWidth()
    {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight()
    {
        return false;
    }
}
