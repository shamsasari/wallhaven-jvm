package com.hibzahim.wallcycle.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

import static org.junit.jupiter.api.condition.OS.WINDOWS;

@EnabledOnOs(WINDOWS)
class WindowsTest {
    private final Windows windows = new Windows();

    @Test
    void isDarkMode() {
        windows.isDarkMode();
    }
}
