package dev.solarion.anticheat.system;

import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ModelTransform;
import com.hypixel.hytale.protocol.SavedMovementStates;
import com.hypixel.hytale.protocol.packets.player.ClientTeleport;
import com.hypixel.hytale.protocol.packets.player.SetMovementStates;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
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
import dev.solarion.anticheat.check.MovementCheck;
import dev.solarion.anticheat.event.RemoveCheatingPlayerEvent;
import com.hypixel.hytale.protocol.GameMode;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ACInputSystem extends EntityTickingSystem<EntityStore> {


    @Nonnull
    private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerInput.getComponentType(), TransformComponent.getComponentType(), MovementManager.getComponentType(), MovementStatesComponent.getComponentType());
    private final Set<Dependency<EntityStore>> deps = Set.of(
            new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class),
            new SystemDependency<>(Order.BEFORE, PlayerSystems.BlockPausedMovementSystem.class),
            new SystemDependency<>(Order.BEFORE, MountSystems.HandleMountInput.class),
            new SystemDependency<>(Order.BEFORE, DamageSystems.FallDamagePlayers.class),
            new SystemDependency<>(Order.BEFORE, KnockbackPredictionSystems.CaptureKnockbackInput.class)
    );

    public ACInputSystem() {
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
        assert transformComponent != null;
        assert movementStatesComponent != null;

        if (playerComponent.hasPermission("solarion.anticheat.bypass")) {
            return;

        } else if (playerComponent.getGameMode() == GameMode.Creative) {
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
                    var moveCheckFailed = MovementCheck.checkInvalidAbsoluteMovementPacket(
                            absoluteMovement.getX(),
                            absoluteMovement.getY(),
                            absoluteMovement.getZ(),
                            virtualPosition,
                            currentMovementStates,
                            movementManager,
                            dt
                    );

                    if (moveCheckFailed) {
                        cancelInputUpdate(entry, toRemove, archetypeChunk, commandBuffer, index);
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
                        cancelInputUpdate(entry, toRemove, archetypeChunk, commandBuffer, index);
                        // TODO: Send an event here to signal that the player failed the anticheat check
                        var playerRef = archetypeChunk.getReferenceTo(index);
                        var playerRefComponent = commandBuffer.getComponent(playerRef, PlayerRef.getComponentType());
//                        if (playerRefComponent != null) {
//                            removePlayer(playerRefComponent, "Kicked for flying");
//                        }
                    }
                    break;
                default:
            }
        }

        movementUpdateQueue.removeAll(toRemove);
    }

    // TODO: ensure player has not already been queued for removal
    private static void removePlayer(PlayerRef ref, String reason) {
        HytaleServer
                .get()
                .getEventBus()
                .dispatchForAsync(RemoveCheatingPlayerEvent.class)
                .dispatch(new RemoveCheatingPlayerEvent(ref, reason));
    }

    private static void cancelInputUpdate(PlayerInput.InputUpdate inputUpdate,
                                          List<PlayerInput.InputUpdate> removalQueue,
                                          @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                          @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                          int index) {
        var transformComponent = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        var headRotationComponent = archetypeChunk.getComponent(index, HeadRotation.getComponentType());

        if (transformComponent == null || headRotationComponent == null) {
            return;
        }

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

        if (playerRefComponent != null) {
            playerRefComponent.getPacketHandler().write(teleportPacket);
            playerRefComponent.getPacketHandler().write(statePacket);
        }
    }
}
