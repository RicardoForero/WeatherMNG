package view;

import java.awt.*;

/**
 * FlowLayout que respeta el ancho del contenedor padre
 * y hace wrap automático de componentes.
 */
public class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension d = layoutSize(target, false);
        d.width -= (getHgap() + 1);
        return d;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;

            Insets insets = target.getInsets();
            int maxW = targetWidth - insets.left - insets.right - getHgap() * 2;

            Dimension dim = new Dimension(0, 0);
            int rowW = 0, rowH = 0;

            for (int i = 0; i < target.getComponentCount(); i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowW + d.width > maxW) {
                    dim.width = Math.max(dim.width, rowW);
                    dim.height += rowH + getVgap();
                    rowW = 0; rowH = 0;
                }
                if (rowW != 0) rowW += getHgap();
                rowW += d.width;
                rowH = Math.max(rowH, d.height);
            }
            dim.width  = Math.max(dim.width, rowW);
            dim.height += rowH + getVgap() * 2;
            dim.width  += insets.left + insets.right + getHgap() * 2;
            dim.height += insets.top  + insets.bottom;
            return dim;
        }
    }
}
