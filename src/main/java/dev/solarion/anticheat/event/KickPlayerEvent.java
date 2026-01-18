package dev.solarion.anticheat.event;

public class KickPlayerEvent
{
    public static void onRemoveCheatingPlayerEvent(RemoveCheatingPlayerEvent event) {
        event.player.getPacketHandler().disconnect(event.reason);
    }
}
