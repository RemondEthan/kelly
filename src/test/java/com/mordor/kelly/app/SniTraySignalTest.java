package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SniTraySignalTest {
    @Test
    void newIconUsesSniInterfaceAndHasNoPayload() throws Exception {
        var signal = new SniTray.ItemIface.NewIcon("/StatusNotifierItem");

        assertEquals("org.kde.StatusNotifierItem", signal.getInterface());
        assertEquals("NewIcon", signal.getName());
        assertEquals("/StatusNotifierItem", signal.getPath());
        assertTrue(signal.getSig() == null || signal.getSig().isEmpty(),
                "SNI NewIcon must have no arguments");
    }
}
