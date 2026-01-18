package dev.solarion.anticheat.systems;

import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.protocol.packets.player.SetMovementStates;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.player.KnockbackPredictionSystems;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PositionUtil;
import dev.solarion.anticheat.events.RemoveCheatingPlayerEvent;
import com.hypixel.hytale.math.vector.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Set;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import java.util.List;

public class AnticheatSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static class ScanPlayerInput extends EntityTickingSystem<EntityStore> {
        @Nonnull
        private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerInput.getComponentType(), TransformComponent.getComponentType(), MovementManager.getComponentType());
        private final Set<Dependency<EntityStore>> deps = Set.of(
                new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class),
                new SystemDependency<>(Order.BEFORE, PlayerSystems.BlockPausedMovementSystem.class),
                new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class),
                new SystemDependency<>(Order.BEFORE, DamageSystems.FallDamagePlayers.class),
                new SystemDependency<>(Order.BEFORE, KnockbackPredictionSystems.CaptureKnockbackInput.class)
        );

        public ScanPlayerInput() {
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return this.query;
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return this.deps;
        }

        @Override
        public void tick(float dt,
                         int index,
                         @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                         @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            var playerInputComponent = archetypeChunk.getComponent(index, PlayerInput.getComponentType());
            var playerComponent = archetypeChunk.getComponent(index, Player.getComponentType());
            var transformComponent = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
            var movementManager = archetypeChunk.getComponent(index, MovementManager.getComponentType());
            var movementStatesComponent = archetypeChunk.getComponent(index, MovementStatesComponent.getComponentType());

            assert playerInputComponent != null;
            assert playerComponent != null;
            assert movementManager != null;

//            LOGGER.atInfo().log("Starting anticheat scan tick for " + playerComponent.getDisplayName());

            if (playerComponent.hasPermission("solarion.anticheat.bypass")) {
//                LOGGER.atInfo().log("    - Player bypassed anticheat scan due to permission bypass");
                return;

            } else if (playerComponent.getGameMode() == GameMode.Creative) {
//                LOGGER.atInfo().log("    - Player bypassed anticheat scan due to creative mode");
                return;
            }

            var movementUpdateQueue = playerInputComponent.getMovementUpdateQueue();
            List<PlayerInput.InputUpdate> toRemove = new ArrayList<>();
            
            // We need to track where the player is supposed to be as we process their moves
            // We start with their actual position at the beginning of this update
            var virtualPosition = transformComponent.getPosition().clone();

            // We also need to track their movement state as it might change during the update batch
            var currentMovementStates = movementStatesComponent.getMovementStates();

            for (PlayerInput.InputUpdate entry : movementUpdateQueue) {
                switch (entry) {
                    case PlayerInput.RelativeMovement relativeMovement:
                        // accumulated relative movement
                        virtualPosition.add(relativeMovement.getX(), relativeMovement.getY(), relativeMovement.getZ());
                        break;
                    case PlayerInput.AbsoluteMovement absoluteMovement:
                        // Check if this move is valid based on where we last confirmed they were
                        // We compare against the 'virtual' position not where they started the tick
                        var moveCheckFailed = checkInvalidAbsoluteMovementPacket(absoluteMovement, virtualPosition, currentMovementStates, movementManager, dt);
                        
                        if (moveCheckFailed) {
                            cancelInputUpdate(entry, toRemove, archetypeChunk, store, commandBuffer, index);
                            // TODO: Send an event here to signal that the player failed the anticheat check.
                        } else {
                            // Valid movement update our virtual position for the next packet check
                            virtualPosition.x = absoluteMovement.getX();
                            virtualPosition.y = absoluteMovement.getY();
                            virtualPosition.z = absoluteMovement.getZ();
                        }
                        break;
                    case PlayerInput.SetMovementStates movementStates:
                        // Update our local tracking of the player's state
                        currentMovementStates = movementStates.movementStates();

                        if (movementStates.movementStates().flying) {
                            cancelInputUpdate(entry, toRemove, archetypeChunk, store, commandBuffer, index);
                            // TODO: Send an event here to signal that the player failed the anticheat check
//                            var playerRef = archetypeChunk.getReferenceTo(index);
//                            var playerRefComponent = commandBuffer.getComponent(playerRef, PlayerRef.getComponentType());
//                            kickPlayer(playerRefComponent, "Kicked for flying");
                        }
                        break;
                    default:
                }
            }

            movementUpdateQueue.removeAll(toRemove);
        }
    }

    // TODO: Need to find the right way to kick a player using this ECS system
    private static void kickPlayer(PlayerRef ref, String reason) {
        HytaleServer
                .get()
                .getEventBus()
                .dispatchForAsync(RemoveCheatingPlayerEvent.class)
                .dispatch(new RemoveCheatingPlayerEvent(ref, reason));
    }

    // TODO: Eventually we should return more specific info like "SPEED_1" instead of just true/false
    private static boolean checkInvalidAbsoluteMovementPacket(PlayerInput.AbsoluteMovement absoluteMovement, Vector3d previousPosition, MovementStates movementStates, MovementManager movementManager, float deltaTime) {


        if (movementStates.mantling) {
            return false;
        }

        var settings = movementManager.getSettings();

        // New Position (Target)
        var newPosition = new Vector3d(absoluteMovement.getX(), absoluteMovement.getY(), absoluteMovement.getZ());
        
        // Deltas (compared to previous validated position)
        var delta = newPosition.clone().subtract(previousPosition);
        
        // 1. Check horizontal movement (walking, running, etc.)
        var lateralDelta = new Vector3d(delta.x, 0, delta.z);
        var lateralDistance = lateralDelta.length();
        var lateralSpeed = lateralDistance / deltaTime; // Units per second

        double maxLateralSpeed;
        if (movementStates.flying) {
            maxLateralSpeed = settings.horizontalFlySpeed;
        } else {
            // Calculate the max valid speed based on their base speed and sprint multipliers
            // We use the sprint multiplier to be safe and avoid false positives
            maxLateralSpeed = settings.baseSpeed * settings.forwardSprintSpeedMultiplier;
        }
        
        // Add a buffer to account for lag or something
        double lateralLimit = (maxLateralSpeed * 1.3) + 2.0;

        if (lateralSpeed > lateralLimit) {
             // LOGGER.atInfo().log("Failed lateral speed: " + lateralSpeed + " > " + lateralLimit);
             return true;
        }

        // 2. Check vertical movement (jumping/falling)
        var verticalDistance = Math.abs(delta.y);
        var verticalSpeed = verticalDistance / deltaTime;

        double maxVerticalSpeed;
        if (movementStates.flying) {
            maxVerticalSpeed = settings.verticalFlySpeed;
        } else {
            if (delta.y > 0) { // Jumping / Going up
                 // When jumping their speed shouldnt exceed the jump force
                 maxVerticalSpeed = settings.jumpForce;
            } else { // Falling
                 // Just a loose cap for falling speed for now
                 maxVerticalSpeed = 60.0;
            }
        }

        // Add robustness buffer
        double verticalLimit = (maxVerticalSpeed * 1.3) + 2.0;

        if (verticalSpeed > verticalLimit && !movementStates.falling) {
             // If they are falling they might be moving faster than normal
             // But if they aren't marked as 'falling' and are moving this fast it's likely a movement exploit
             
             // LOGGER.atInfo().log("Failed vertical speed: " + verticalSpeed + " > " + verticalLimit);
             return true;
        }
        
        return false;
    }

    private static void cancelInputUpdate(PlayerInput.InputUpdate inputUpdate,
                                          List<PlayerInput.InputUpdate> removalQueue,
                                          @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                          int index) {
        var transformComponent = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        var headRotationComponent = archetypeChunk.getComponent(index, HeadRotation.getComponentType());

        removalQueue.add(inputUpdate);

        var playerRef = archetypeChunk.getReferenceTo(index);
        var position = transformComponent.getPosition();
        var rotation = transformComponent.getRotation();
        var headRotation = headRotationComponent.getRotation();

        var playerRefComponent = commandBuffer.getComponent(playerRef, PlayerRef.getComponentType());
        var teleportPacket = new ClientTeleport(
                (byte)0,
                new ModelTransform(
                        PositionUtil.toPositionPacket(position),
                        PositionUtil.toDirectionPacket(rotation),
                        PositionUtil.toDirectionPacket(headRotation)
                ),
                false
        );
        var statePacket = new SetMovementStates(new SavedMovementStates(false));

        playerRefComponent.getPacketHandler().write(teleportPacket);
        playerRefComponent.getPacketHandler().write(statePacket);
    }
}
