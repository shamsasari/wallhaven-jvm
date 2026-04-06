package shamsasari.wallhaven.os;

public sealed interface OperatingSystem permits Windows {
    boolean isDarkMode();
    void setWallpaper(byte[] image);
}
