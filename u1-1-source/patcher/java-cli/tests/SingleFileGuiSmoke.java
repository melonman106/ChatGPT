package com.eaglercraft.patcher;

import java.awt.Dialog;
import java.awt.Window;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Run on an isolated X display. Opens the real setup window and folder picker. */
public final class SingleFileGuiSmoke {
    public static void main(String[] args) throws Exception {
        SingleFileLauncher.main(new String[0]);
        CountDownLatch opened = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            JFrame setup = null;
            for (Window window : Window.getWindows()) {
                if (window instanceof JFrame frame && frame.getTitle().contains("Patcher Setup")) setup = frame;
            }
            if (setup == null || !setup.isVisible()) throw new AssertionError("Setup window missing");
            JButton button = findButton(setup);
            if (button == null || !button.getText().contains("Choose setup folder")) {
                throw new AssertionError("Setup folder button missing");
            }
            opened.countDown();
            button.doClick();
        });
        if (!opened.await(10, TimeUnit.SECONDS)) throw new AssertionError("Setup window timeout");
        boolean picker = false;
        for (int attempt = 0; attempt < 30; attempt++) {
            Thread.sleep(100);
            for (Window window : Window.getWindows()) {
                if (window instanceof Dialog dialog && dialog.isVisible()
                        && findChooser(dialog) != null) picker = true;
            }
            if (picker) break;
        }
        if (!picker) throw new AssertionError("Folder picker did not open");
        System.out.println("SingleFileGuiSmoke: setup window and folder picker PASS");
        System.exit(0);
    }

    private static JButton findButton(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JButton button) return button;
            if (child instanceof java.awt.Container container) {
                JButton nested = findButton(container);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static JFileChooser findChooser(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JFileChooser chooser) return chooser;
            if (child instanceof java.awt.Container container) {
                JFileChooser nested = findChooser(container);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
