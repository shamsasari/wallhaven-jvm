package com.hibzahim.wallcycle.os;

public sealed interface OperatingSystem permits Windows {
    boolean isDarkMode();
    void setWallpaper(byte[] image);

    default void onDarkModeChange(Runnable listener) {
        var base = isDarkMode();
        try {
            while (true) {
                Thread.sleep(2_000);
                var current = isDarkMode();
                if (current != base) {
                    listener.run();
                    base = current;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
