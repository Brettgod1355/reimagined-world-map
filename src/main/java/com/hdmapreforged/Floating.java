package com.hdmapreforged;

import java.awt.*;
import javax.swing.*;
import net.runelite.client.ui.*;

/** A panel floating over the map (card, search results, custom routes): a title bar with fold and close buttons. */
final class Floating extends JPanel
{
    static final int BAR = 24;

    private final JPanel bar = new JPanel(new BorderLayout(4, 0));
    private final JButton fold = small("–", "Fold up");
    private JComponent content;
    private boolean folded;

    Floating(String title, Runnable close)
    {
        super(new BorderLayout());
        setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 45)));
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        bar.setBackground(new Color(24, 26, 30));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 2));
        JLabel name = new JLabel(title);
        name.setForeground(new Color(200, 200, 205));
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        bar.add(name, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new BorderLayout(2, 0));
        buttons.setOpaque(false);
        fold.addActionListener(e -> setFolded(!folded));
        buttons.add(fold, BorderLayout.WEST);
        JButton shut = small("×", "Close");
        shut.addActionListener(e -> close.run());
        buttons.add(shut, BorderLayout.EAST);
        bar.add(buttons, BorderLayout.EAST);
        bar.setPreferredSize(new Dimension(10, BAR));
        add(bar, BorderLayout.NORTH);
    }

    /** Replaces the content below the bar, matching the current fold state. */
    void setContent(JComponent shown)
    {
        if (content != null && content.getParent() == this)
        {
            remove(content);
        }
        content = shown;
        add(shown, BorderLayout.CENTER);
        shown.setVisible(!folded);
        revalidate();
    }

    void setFolded(boolean fold)
    {
        folded = fold;
        this.fold.setText(fold ? "+" : "–");
        this.fold.setToolTipText(fold ? "Unfold" : "Fold up");
        if (content != null)
        {
            content.setVisible(!fold);
        }
        firePropertyChange("folded", !fold, fold);
        revalidate();
        repaint();
    }

    /** Only its bar when folded, else the bar and {@code contentHeight}. */
    int height(int contentHeight)
    {
        return BAR + 2 + (folded ? 0 : contentHeight);
    }

    private static JButton small(String text, String tip)
    {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 6, 0, 6));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setForeground(new Color(200, 200, 205));
        button.setToolTipText(tip);
        return button;
    }
}
