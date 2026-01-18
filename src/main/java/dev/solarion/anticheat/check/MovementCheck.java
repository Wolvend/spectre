package dev.solarion.anticheat.check;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementStates;

public class MovementCheck {

    public static final double MAX_STEP_HEIGHT = 1.1;

    // TODO: Eventually we should return more specific info like "SPEED_1" instead of just true/false
    public static boolean checkInvalidAbsoluteMovementPacket(double x, double y, double z, Vector3d previousPosition, MovementStates movementStates, MovementManager movementManager, float deltaTime) {

        // Allows auto step-up teleporting
        // The client teleports up by 1 block when performing an auto step-up.
        // The client stays on ground when stepping up so we make sure to check for that.
        if (movementStates.onGround && !movementStates.mantling) {
            double deltaY = y - previousPosition.y;

            if (deltaY > 0 && deltaY <= MAX_STEP_HEIGHT) {
                return false;
            }
        }

        // Disallows the mantle-into-ceiling warp glitch
        if (movementStates.onGround && movementStates.mantling) {
            return true;
        }

        // Fix to allow mantling
        if (movementStates.mantling) {
            return false;
        }

        // New Position (Target)
        var newPosition = new Vector3d(x, y, z);

        // Deltas (compared to previous validated position)
        var delta = newPosition.clone().subtract(previousPosition);

        // 1. Check horizontal movement (walking running etc.)
        var lateralDelta = new Vector3d(delta.x, 0, delta.z);
        var lateralDistance = lateralDelta.length();
        var lateralSpeed = lateralDistance / deltaTime; // Units per second

        double lateralLimit = getLateralLimit(movementStates, movementManager);

        if (lateralSpeed > lateralLimit) {
            // LOGGER.atInfo().log("Failed lateral speed: " + lateralSpeed + " > " + lateralLimit);
            return true;
        }

        // 2. Check vertical movement (jumping/falling)
        var verticalDistance = Math.abs(delta.y);
        var verticalSpeed = verticalDistance / deltaTime;

        double verticalLimit = getVerticalLimit(movementStates, movementManager, delta.y);

        // If they are falling they might be moving faster than normal
        // But if they aren't marked as 'falling' and are moving this fast it's likely a movement exploit
        // LOGGER.atInfo().log("Failed vertical speed: " + verticalSpeed + " > " + verticalLimit);
        return verticalSpeed > verticalLimit && !movementStates.falling;
    }

    private static double getVerticalLimit(MovementStates movementStates, MovementManager movementManager, double deltaY) {
        var settings = movementManager.getSettings();
        double maxVerticalSpeed;
        if (movementStates.flying) {
            maxVerticalSpeed = settings.verticalFlySpeed;
        } else {
            if (deltaY > 0) { // Jumping / Going up
                // When jumping their speed shouldn't exceed the jump force
                maxVerticalSpeed = settings.jumpForce;
            } else { // Falling
                // Just a loose cap for falling speed for now
                maxVerticalSpeed = 60.0;
            }
        }

        // Add robustness buffer
        return (maxVerticalSpeed * 1.3) + 2.0;
    }

    private static double getLateralLimit(MovementStates movementStates, MovementManager movementManager) {
        var settings = movementManager.getSettings();
        double maxLateralSpeed;
        if (movementStates.flying) {
            maxLateralSpeed = settings.horizontalFlySpeed;
        } else {
            // Calculate the max valid speed based on their base speed and sprint multipliers
            // We use the sprint multiplier to be safe and avoid false positives
            maxLateralSpeed = settings.baseSpeed * settings.forwardSprintSpeedMultiplier;
        }

        // Add a buffer to account for lag or something
        return (maxLateralSpeed * 1.3) + 4.0;
    }
}
