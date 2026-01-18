package dev.solarion.anticheat.event;

import com.hypixel.hytale.server.core.HytaleServer;

public class KickPlayerOnRemove
{
    public static void onRemoveCheatingPlayerEvent(RemoveCheatingPlayerEvent event) {
        HytaleServer.SCHEDULED_EXECUTOR.execute(() -> {
            event.player.getPacketHandler().disconnect(event.reason);
        });
    }
}
